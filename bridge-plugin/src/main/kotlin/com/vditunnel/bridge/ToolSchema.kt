package com.vditunnel.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Tiny shared builders for the tools/list schema entries LocalFileTools/ProcessTools splice
 *  into the IDE's own tools/list reply. Package-visible so both can use the same three. */

fun tool(name: String, description: String, properties: JsonObject, required: List<String>): JsonObject {
    val t = JsonObject()
    t.addProperty("name", name)
    t.addProperty("description", description)
    val schema = JsonObject()
    schema.addProperty("type", "object")
    schema.add("properties", properties)
    val req = JsonArray()
    required.forEach { req.add(it) }
    schema.add("required", req)
    t.add("inputSchema", schema)
    return t
}

fun prop(type: String, description: String): JsonObject {
    val p = JsonObject()
    p.addProperty("type", type)
    p.addProperty("description", description)
    return p
}

fun obj(vararg pairs: Pair<String, JsonObject>): JsonObject {
    val o = JsonObject()
    pairs.forEach { (k, v) -> o.add(k, v) }
    return o
}
