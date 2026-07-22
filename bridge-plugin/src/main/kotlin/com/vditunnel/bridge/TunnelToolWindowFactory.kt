package com.vditunnel.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class TunnelToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // IDE MCP server offers /sse and /stream on 64342; we speak streamable HTTP (/stream).
        val ide = McpLocalClient(System.getProperty("vdi.ide.mcp.url", "http://127.0.0.1:64342/stream"))
        val controller = TunnelController(ide)
        val panel = TunnelPanel(controller, project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
