package cc.bridgit.app.interact

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TableSortTest {
    private fun row(id: Int, value: Any?) = JSONObject().put("id", id).put("v", value ?: JSONObject.NULL)
    @Test fun numbersToggleAndMissingValuesStayLast() {
        val rows = listOf(row(1, 100), row(2, 9), row(3, null), row(4, 9))
        fun ids(descending: Boolean) = TableSort.rows(rows, "v", descending).map { it.getInt("id") }
        assertEquals(listOf(2, 4, 1, 3), ids(false))
        assertEquals(listOf(1, 2, 4, 3), ids(true))
        assertEquals(rows, TableSort.rows(rows, null, false))
    }
    @Test fun textIgnoresCaseAndRetainsTies() {
        val rows = listOf(row(1, "Zoo"), row(2, "apple"), row(3, "Apple"))
        assertEquals(listOf(2, 3, 1), TableSort.rows(rows, "v", false).map { it.getInt("id") })
    }
}
