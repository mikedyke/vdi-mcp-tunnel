package com.vditunnel.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.*
import java.awt.event.HierarchyEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** The tool-window UI: plain textarea (uplink) + canvas (downlink QR, heartbeat, fiducials, focus glyph).
 *  Panel is ONE calibrated unit — the host locates the 4 corner ArUco markers and derives every
 *  other point (QR ROI, textarea click-point) as a fixed offset. Keep this layout stable. */
class TunnelPanel(
    private val controller: TunnelController,
    private val project: Project? = null,
) : JPanel(BorderLayout()) {

    private val textArea = JTextArea(6, 40).apply {
        lineWrap = false
        isEditable = true
        // Plain component: no completion / bracket matching / IME smart-edits to mangle typed JSON.
    }
    private val canvas = Canvas()
    private var lastConsumed = 0   // chars of textArea already turned into lines

    init {
        preferredSize = Dimension(560, 900)
        add(canvas, BorderLayout.CENTER)
        add(JScrollPane(textArea), BorderLayout.SOUTH)

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = drain()
            // The host clears the field (select-all + delete) before each request so every
            // request starts clean. Keep lastConsumed in range so we resume parsing from the
            // right offset (0 after a full clear) instead of pointing past the end.
            override fun removeUpdate(e: DocumentEvent) {
                if (lastConsumed > e.document.length) lastConsumed = e.document.length
            }
            override fun changedUpdate(e: DocumentEvent) {}
        })

        // downlink animation + heartbeat repaint. Each paint advances one RESP symbol;
        // 6 fps roughly halves large-reply transfer time vs 3 (host capture keeps up).
        Timer(1000 / 6) { canvas.repaint() }.start()   // downlink fps

        // Undocked, the panel drops behind the IDE frame as soon as anything raises the IDE
        // -- running a terminal command, opening a dialog. The host then cannot see the four
        // fiducials and fails with "panel not found", or worse, types into whatever is in
        // front. Pin the panel's own window on top so it stays capturable and clickable.
        // Escape hatch: -Dvdi.tunnel.always.on.top=false
        addHierarchyListener { e ->
            val moved = HierarchyEvent.SHOWING_CHANGED or HierarchyEvent.PARENT_CHANGED
            if (e.changeFlags and moved.toLong() != 0L) applyAlwaysOnTop()
        }
    }

    /** Only ever applied to a separate tool-window window. When the panel is docked the
     *  ancestor IS the main IDE frame, and pinning that would hold the whole IDE above every
     *  other application on the VDI desktop. */
    private fun applyAlwaysOnTop() {
        if (System.getProperty("vdi.tunnel.always.on.top", "true") != "true") return
        val window = SwingUtilities.getWindowAncestor(this) ?: return
        if (window === project?.let { WindowManager.getInstance().getFrame(it) }) return
        if (!Toolkit.getDefaultToolkit().isAlwaysOnTopSupported) return
        try {
            if (window.isAlwaysOnTopSupported && !window.isAlwaysOnTop) window.isAlwaysOnTop = true
        } catch (_: Exception) {
            // SecurityException / unsupported window kind: leave it, the panel still works
            // docked or if the user keeps it visible manually.
        }
    }

    /** Turn newline-terminated content into complete lines fed to the controller. */
    private fun drain() {
        val text = textArea.text
        var nl = text.indexOf('\n', lastConsumed)
        while (nl >= 0) {
            val line = text.substring(lastConsumed, nl)
            lastConsumed = nl + 1
            if (line.isNotBlank()) controller.acceptLine(line)
            nl = text.indexOf('\n', lastConsumed)
        }
        // keep the textarea from growing unbounded
        if (lastConsumed > 20000) {
            SwingUtilities.invokeLater {
                textArea.text = ""; lastConsumed = 0
            }
        }
    }

    private inner class Canvas : JComponent() {
        private val markers: Array<BufferedImage?> = Array(4) { loadMarker(it) }
        private val M = 64   // marker size px

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.color = Color.WHITE; g2.fillRect(0, 0, width, height)

            // 4 ArUco fiducials at panel corners (ids 0=TL,1=TR,2=BR,3=BL)
            drawMarker(g2, 0, 0, 0)
            drawMarker(g2, 1, width - M, 0)
            drawMarker(g2, 2, width - M, height - M)
            drawMarker(g2, 3, 0, height - M)

            val focusTop = height - M - 220   // focus bar row; everything below is reserved

            // Downlink QR footprint — computed the same whether or not a frame is active, so
            // the sizing guide below shows where/how big the QR renders while you size the
            // window. Bounded by BOTH width and the gap above the focus bar so the QR never
            // overlaps the focus/heartbeat row (a bar through the QR corrupts it).
            val qrTop = M + 10
            val qrS = minOf(width - 2 * M - 20, focusTop - qrTop - 15, 640).coerceAtLeast(64)
            val qrX = (width - qrS) / 2

            // main downlink QR (centre-upper), drawn only while sending a response
            controller.currentDownlinkImage()?.let { g2.drawImage(it, qrX, qrTop, qrS, qrS, null) }

            // sizing guide: a rectangle just OUTSIDE the QR quiet zone (never touches the code)
            // + a px/module readout. Green = dense enough for the host to capture; amber = the
            // window is too small, make it wider/taller until this turns green.
            val pxPerModule = qrS / 93f          // ~V18 (89 modules) + 2-module quiet zone each side
            val ok = pxPerModule >= 4f
            g2.color = if (ok) Color(0, 150, 0) else Color(210, 140, 0)
            g2.stroke = BasicStroke(2f)
            g2.drawRect(qrX - 6, qrTop - 6, qrS + 12, qrS + 12)
            g2.font = g2.font.deriveFont(11f)
            g2.drawString("QR ${qrS}px  ${"%.1f".format(pxPerModule)} px/module  ${if (ok) "OK" else "widen window"}",
                          qrX - 6, qrTop - 10)

            // heartbeat QR (bottom-right corner region — host PANEL_LAYOUT.heartbeat_roi)
            g2.drawImage(controller.heartbeatImage(160), width - M - 170, height - M - 170, 160, 160, null)

            // focus indicator: border colour flips when the textarea is focused (host verifies before typing)
            g2.color = if (textArea.hasFocus()) Color(0, 180, 0) else Color(200, 0, 0)
            g2.stroke = BasicStroke(6f)
            g2.drawRect(M, focusTop, width - 2 * M, 30)
        }

        private fun drawMarker(g2: Graphics2D, id: Int, x: Int, y: Int) {
            val img = markers[id]
            if (img != null) g2.drawImage(img, x, y, M, M, null)
            else { // PLACEHOLDER — will NOT be detected. Generate real markers (see README).
                g2.color = Color.BLACK; g2.fillRect(x, y, M, M)
                g2.color = Color.WHITE; g2.drawString("aruco$id?", x + 4, y + M / 2)
            }
        }

        private fun loadMarker(id: Int): BufferedImage? =
            javaClass.getResourceAsStream("/markers/aruco_$id.png")?.use { ImageIO.read(it) }
    }
}
