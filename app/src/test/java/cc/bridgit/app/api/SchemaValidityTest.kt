package cc.bridgit.app.api

import cc.bridgit.app.platform.Commands
import cc.bridgit.app.platform.PlatformApi
import cc.bridgit.app.platform.ToolSchemas
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every machine-readable schema agents read cold (MCP `/v1/mcp` and `/mcp`, `/v1/openapi.json`,
 * `/v2/tools`) must be valid JSON Schema: real type names, required fields that exist, no empty enums,
 * and tool roots a plain object (the Claude API rejects a top-level oneOf/anyOf/allOf).
 */
class SchemaValidityTest {
    private val types = setOf("string", "integer", "number", "boolean", "object", "array", "null")
    private val combinators = listOf("oneOf", "anyOf", "allOf")

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.get(it) as? String ?: error("non-string in $a") }

    private fun typesOf(where: String, s: JSONObject): List<String> {
        val t = s.opt("type") ?: return emptyList()
        val list = when (t) {
            is String -> listOf(t)
            is JSONArray -> strings(t).also { assertTrue("$where: empty type array", it.isNotEmpty()); assertEquals("$where: repeated type", it.size, it.toSet().size) }
            else -> { fail("$where: type is $t"); emptyList() }
        }
        list.forEach { assertTrue("$where: invalid type '$it'", it in types) }
        return list
    }

    /** Checks one schema and every schema nested in it. */
    private fun check(where: String, s: JSONObject) {
        assertTrue("$where: schema has no type, \$ref or combinator", s.has("type") || s.has("\$ref") || combinators.any(s::has))
        val t = typesOf(where, s)
        s.optJSONArray("enum")?.let { e ->
            assertTrue("$where: empty enum", e.length() > 0)
            if (t == listOf("string")) strings(e)
        }
        val props = s.optJSONObject("properties")
        if (props != null) assertTrue("$where: properties on a non-object", "object" in t)
        s.optJSONArray("required")?.let { r ->
            for (name in strings(r)) assertTrue("$where: required '$name' is not a property", props?.has(name) == true)
            assertEquals("$where: repeated required", r.length(), strings(r).toSet().size)
        }
        props?.keys()?.forEach { k -> check("$where.$k", props.getJSONObject(k)) }
        s.optJSONObject("items")?.let { assertTrue("$where: items on a non-array", "array" in t); check("$where[]", it) }
        (s.opt("additionalProperties") as? JSONObject)?.let { check("$where.additionalProperties", it) }
        for (c in combinators + listOf("prefixItems")) s.optJSONArray(c)?.let { a -> for (i in 0 until a.length()) check("$where.$c[$i]", a.getJSONObject(i)) }
        for (c in listOf("if", "then", "else", "not")) s.optJSONObject(c)?.let { check("$where.$c", it) }
        for (n in listOf("minimum", "maximum", "maxLength", "minItems", "maxItems")) if (s.has(n)) assertTrue("$where: $n is not a number", s.get(n) is Number)
    }

    private fun checkToolRoot(where: String, s: JSONObject) {
        assertEquals("$where: tool schema root must be an object", "object", s.get("type"))
        combinators.forEach { assertTrue("$where: top-level $it breaks Claude-based MCP clients", !s.has(it)) }
        assertTrue("$where: no properties", s.has("properties"))
        check(where, s)
    }

    @Test fun mainMcpToolSchemasAreValid() {
        for (e in McpTools.specs) checkToolRoot(McpTools.name(e), McpTools.inputSchema(e))
    }

    /** Validate the structural JSON Schema facets emitted for the exact documented request. */
    private fun validateExample(where: String, value: Any, schema: JSONObject) {
        val actual = when (value) {
            JSONObject.NULL -> "null"
            is JSONObject -> "object"
            is JSONArray -> "array"
            is String -> "string"
            is Boolean -> "boolean"
            is Number -> if (value.toDouble() % 1.0 == 0.0) "integer" else "number"
            else -> error("Unsupported example value at $where")
        }
        val allowed = typesOf(where, schema)
        assertTrue("$where: expected $allowed, got $actual", actual in allowed || actual == "integer" && "number" in allowed)
        schema.optJSONArray("enum")?.let { a -> assertTrue("$where: value outside enum", (0 until a.length()).any { a.get(it) == value }) }
        if (value is JSONObject) {
            schema.optJSONArray("required")?.let { a -> strings(a).forEach { assertTrue("$where: missing $it", value.has(it)) } }
            schema.optJSONObject("properties")?.let { props -> value.keys().forEach { key ->
                props.optJSONObject(key)?.let { validateExample("$where.$key", value.get(key), it) }
            } }
        }
        if (value is JSONArray) schema.optJSONObject("items")?.let { item ->
            for (i in 0 until value.length()) validateExample("$where[$i]", value.get(i), item)
        }
    }

    @Test fun documentedCameraWatchPassesMcpAndOpenApiSchemasAndRuntimeParser() {
        val endpoint = cc.bridgit.app.watch.WatchModule.CREATE
        val body = JSONObject(endpoint.example.substring(endpoint.example.indexOf('{')))
        val mcp = McpTools.inputSchema(endpoint)
        validateExample("watch", body, mcp)
        val openApi = OpenApi.build("test", Address(null, null, 0)).getJSONObject("paths")
            .getJSONObject(endpoint.fullPath).getJSONObject("post").getJSONObject("requestBody")
            .getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
        validateExample("watch", body, openApi)
        val routed = McpTools.route(endpoint, body)
        assertEquals(listOf("camera"), cc.bridgit.app.watch.WatchRules.parse(routed.body).data)
        val wrong = JSONObject(body.toString()).put("data", JSONArray().put(JSONObject().put("camera", true)))
        try { validateExample("watch", wrong, mcp); fail("Object-valued watch data unexpectedly accepted") }
        catch (e: AssertionError) { assertTrue(e.message.orEmpty().contains("watch.data[0]")) }
        // Object-valued arrays remain objects; the string mapping only applies to array parameters.
        assertEquals("object", McpTools.inputSchema(Endpoints.LOG_NOTE).getJSONObject("properties").getJSONObject("data").getString("type"))
        assertEquals("object", McpTools.arrayItemType("controls"))
        assertEquals("object", McpTools.arrayItemType("rules"))
    }

    @Test fun unionParamTypesBecomeTypeArrays() {
        val unions = Endpoints.all.flatMap { e -> (e.query + e.body).filter { '|' in it.type }.map { e to it } }
        assertTrue("expected the state and callback_when unions", unions.isNotEmpty())
        for ((e, p) in unions) {
            val s = McpTools.inputSchema(e).getJSONObject("properties").getJSONObject(p.name)
            assertEquals("${e.path} ${p.name}", p.type.split('|'), strings(s.getJSONArray("type")))
        }
    }

    @Test fun openApiDocumentSchemasAreValid() {
        val o = OpenApi.build("1.0.0", Address("phone.ts.net", "100.64.0.1", 8735))
        val components = o.getJSONObject("components").getJSONObject("schemas")
        components.keys().forEach { check("components.$it", components.getJSONObject(it)) }
        val paths = o.getJSONObject("paths")
        var count = 0
        for (path in paths.keys()) {
            val ops = paths.getJSONObject(path)
            for (method in ops.keys()) {
                val op = ops.getJSONObject(method); val where = "$method $path"
                op.optJSONArray("parameters")?.let { a -> for (i in 0 until a.length()) { val p = a.getJSONObject(i); check("$where ${p.getString("name")}", p.getJSONObject("schema")); count++ } }
                op.optJSONObject("requestBody")?.getJSONObject("content")?.let { c -> c.keys().forEach { check("$where body", c.getJSONObject(it).getJSONObject("schema")); count++ } }
            }
        }
        assertTrue(count > 20)
    }

    @Test fun alternateKeyFieldsAreStringsInCanonicalSchemas() {
        val schema = McpTools.inputSchema(cc.bridgit.app.capability.SettingsConfigurationApi.KEYS_SET)
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("fields").getJSONObject("items").getString("type"))
    }

    @Test fun controlToolSchemasAreValid() {
        for (name in Commands.all) checkToolRoot(name, ToolSchemas.forTool(name))
        val listed = PlatformApi.schema()
        assertEquals((Commands.all).size, listed.length())
        for (i in 0 until listed.length()) listed.getJSONObject(i).let { checkToolRoot(it.getString("name"), it.getJSONObject("inputSchema")) }
    }

    private fun required(name: String) = strings(ToolSchemas.forTool(name).getJSONArray("required")).toSet()

    @Test fun controlToolsRequireWhatTheirHandlersRequire() {
        val expected = mapOf(
            "translation.run" to setOf("source", "target", "text"),
            "translation.download" to setOf("source", "target"),
            "notification.reply" to setOf("id", "fingerprint", "text"),
            "session.request" to setOf("apps", "purpose"),
            "screen.wait" to setOf("session_id", "expect"),
            "ui.action" to setOf("session_id", "fence", "observation_id", "type"),
            "ui.sequence" to setOf("session_id", "fence", "steps"),
            "device.action" to setOf("type"),
            "peripheral.action" to setOf("type"),
            "file.put" to setOf("name", "mime", "data_base64"),
            "workflow.propose" to setOf("name", "trigger", "steps"),
            "workflow.validate" to setOf("name", "trigger", "steps"),
            "alert.trigger" to setOf("destinations", "text"),
            "interaction.create" to setOf("title"),
            "operation.get" to setOf("id"), "file.get" to setOf("id"), "stream.frame" to setOf("id"),
            "note.create" to setOf("text"),
        )
        for ((name, fields) in expected) assertEquals(name, fields + if (name in Commands.mutations) setOf("idempotency_key") else emptySet(), required(name))
        // Exactly one of three image sources: no field is always required, and the schema says so.
        val ocr = ToolSchemas.forTool("images.ocr")
        assertEquals(emptySet<String>(), required("images.ocr"))
        listOf("file_id", "gallery_id", "image_base64").forEach { assertTrue(ocr.getString("description").contains(it)) }
        // Enums match the handlers' fixed choices.
        val action = ToolSchemas.forTool("device.action").getJSONObject("properties")
        assertEquals(ToolSchemas.DEVICE_ACTIONS, strings(action.getJSONObject("type").getJSONArray("enum")))
        assertEquals(listOf("rear", "front"), strings(action.getJSONObject("camera").getJSONArray("enum")))
    }
}
