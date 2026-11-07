package cc.bridgit.app.interact

import org.json.JSONObject

/** Shared table ordering uses raw values, keeps missing values last and preserves ties. */
object TableSort {
    fun rows(rows: List<JSONObject>, column: String?, descending: Boolean): List<JSONObject> {
        if (column == null) return rows
        return rows.sortedWith { a, b ->
            val x = a.opt(column)?.takeUnless { it == JSONObject.NULL || it == "" }
            val y = b.opt(column)?.takeUnless { it == JSONObject.NULL || it == "" }
            when {
                x == null -> if (y == null) 0 else 1
                y == null -> -1
                else -> {
                    val cmp = when {
                        x is Number && y is Number -> x.toDouble().compareTo(y.toDouble())
                        x is Boolean && y is Boolean -> x.compareTo(y)
                        else -> x.toString().compareTo(y.toString(), ignoreCase = true)
                    }
                    if (descending) -cmp else cmp
                }
            }
        }
    }
}
