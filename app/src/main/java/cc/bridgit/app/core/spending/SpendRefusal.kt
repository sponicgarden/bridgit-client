package cc.bridgit.app.core.spending

/**
 * Why the spending core said no. This is the core's own value, not an API answer: nothing here is returned
 * to a caller yet.
 *
 * - [rule] is the rule of G6 that failed (1 to 8), when a rule did. Rules run in order and the first failure
 *   decides, so it is the only one named.
 * - [code] is an error code of the contract pack (`contracts/client-core/errors.json`, all `built: false`
 *   until a door returns them), for the refusals the design gives a code. For rules 1 to 7 the design says
 *   "refuse" and names none; the wire code for those is the configuration and door step's to choose (G11),
 *   so it is null here.
 * - [reason] is the finer word that rides beside it.
 * - [resetsAt] is when the refusal ends by itself, where the design asks for `resets_at` (rules 5, 6 and 8).
 *   It is only given when the device has a time it can reason from.
 */
data class SpendRefusal(val code: Code?, val reason: Reason? = null, val rule: Int? = null, val resetsAt: Long? = null) {
    enum class Code(val id: String) {
        /** Cloud-held instrument and the cloud cannot be reached (G8 row 1). */
        CLOUD_UNREACHABLE("cloud_unreachable"),

        /** The device reaches nothing (G7.2). */
        OFFLINE("offline"),

        /** No room in the held allowance for this period (G6 rule 8). */
        ALLOWANCE_EXHAUSTED("allowance_exhausted"),

        /** The held allowance ran out of time (G5.2). */
        ALLOWANCE_EXPIRED("allowance_expired"),

        /** The spend names another namespace than its source's (G10.3 X10). */
        NAMESPACE_MISMATCH("namespace_mismatch"),

        /** A move of budget room names another recipient (G5.1, SA-R1). */
        TRANSFER_NOT_FOR_RECIPIENT("transfer_not_for_recipient"),
    }

    enum class Reason(val id: String) {
        /** The journal was not whole, so a new epoch began and every current period counts as fully used (G5.3 rule 3). */
        JOURNAL_UNVERIFIED("journal_unverified"),

        /** No trusted time has opened the period (G5.2): the device rebooted and no anchor has arrived, so the old count stands. */
        TIME_UNANCHORED("time_unanchored"),

        /** No journal entry has opened this period of this rule yet, so it counts as fully used. */
        PERIOD_NOT_OPEN("period_not_open"),

        /** A settlement took this period's count past what can be counted; it refuses everything until the next period (G5.4). */
        COUNT_OVERFLOW("count_overflow"),

        /** A row of an earlier epoch can be tied to no spend, so no request can be shown to be new ([Journal.acknowledgeHistory] is the way out). */
        HISTORY_UNREADABLE("history_unreadable"),

        /** The line's unit is not the spend's; a fresh valuation is the rule engine's to bring (G2.2 "Reference currency"). */
        UNIT_MISMATCH("unit_mismatch"),

        /** Rule 1: outside calls are paused, on the device or in the rule document. */
        PAUSED("paused"),

        /** Rule 1: spending is off for the instrument class or for the source. */
        SWITCHED_OFF("switched_off"),

        /** Rule 1: payments are off (§9). */
        PAYMENTS_OFF("payments_off"),

        /** Rule 2: the device does not let this agent or applet act. */
        MAY_NOT_ACT("may_not_act"),

        /** Rule 2: a value transfer needs `payments.request`, given only by an owner authorization. */
        PAYMENTS_REQUEST_REQUIRED("payments_request_required"),

        /** Rule 3: the source is not linked on this device. */
        SOURCE_NOT_LINKED("source_not_linked"),

        /** Rule 3: the model or route is not on the source's allow-list. */
        ROUTE_NOT_ALLOWED("route_not_allowed"),

        /** Rule 4: the spend is above the per-spend cap. */
        PER_SPEND_CAP("per_spend_cap"),

        /** Rule 5: a Bridgit-funded cap, as the cloud last reported it. */
        BRIDGIT_CAP("bridgit_cap"),

        /** Rule 6: an owner budget has no room left in this device's portion for the period. */
        BUDGET_REACHED("budget_portion_used"),

        /** Rule 7: a rate limit. */
        RATE_LIMITED("spend_rate"),

        /** Rule 8: no budget gives this device a share for the spend, or a leased share has no slice. */
        NO_ALLOWANCE("no_allowance"),

        /** Rule 8: after a reset, the share is frozen until the install's record is rebuilt from the other executors (SA-R6). */
        FROZEN_AFTER_RESET("frozen_after_reset"),

        /** The part of the design this spend needs is not in this build: the cloud path, or rules 9 and 10 for a value transfer. Fails closed. */
        NOT_BUILT("not_built"),
    }
}
