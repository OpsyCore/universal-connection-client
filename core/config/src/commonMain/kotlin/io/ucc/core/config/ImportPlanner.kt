package io.ucc.core.config

import io.ucc.core.model.ConnectionProfile

/**
 * Turns an [ImportReport] into a user-confirmable [ImportPlan]:
 *  - structural validation ([ProfileValidator])
 *  - core capability check ([CapabilityCheck])
 *  - duplicate detection by **fingerprint** against the existing store
 *    (never by display name), and within the batch itself
 *
 * Pure function of its inputs — the UI only renders it and collects the user's
 * confirmation; persistence happens elsewhere.
 */
public class ImportPlanner(private val capabilityCheck: CapabilityCheck) {

    public fun plan(report: ImportReport, existing: Collection<ConnectionProfile>): ImportPlan {
        val existingByFingerprint = existing.associateBy { it.fingerprint }
        val seenInBatch = HashSet<String>()
        val items = ArrayList<ImportItem>(report.profiles.size)
        for (profile in report.profiles) {
            val structural = ProfileValidator.validate(profile)
            if (structural.isNotEmpty()) {
                items += ImportItem(profile, ImportItem.Status.Invalid(structural), selectedByDefault = false)
                continue
            }
            val fp = profile.fingerprint
            val existingMatch = existingByFingerprint[fp]
            val unsupported = capabilityCheck.unsupportedReason(profile)
            val status = when {
                existingMatch != null -> ImportItem.Status.Duplicate(existingMatch.id, existingMatch.name)
                !seenInBatch.add(fp) -> ImportItem.Status.DuplicateInBatch
                unsupported != null -> ImportItem.Status.Unsupported(unsupported)
                else -> ImportItem.Status.New
            }
            // Unsupported profiles can still be stored (another core may carry them); they are just not preselected.
            items += ImportItem(profile, status, selectedByDefault = status is ImportItem.Status.New)
        }
        return ImportPlan(format = report.format, items = items, failures = report.failures)
    }
}

public data class ImportPlan(
    val format: InputFormat,
    val items: List<ImportItem>,
    val failures: List<ImportFailure>,
) {
    val newCount: Int get() = items.count { it.status is ImportItem.Status.New }
    val duplicateCount: Int get() = items.count { it.status is ImportItem.Status.Duplicate || it.status is ImportItem.Status.DuplicateInBatch }
    val unsupportedCount: Int get() = items.count { it.status is ImportItem.Status.Unsupported }
    val invalidCount: Int get() = items.count { it.status is ImportItem.Status.Invalid }
    val hasAnythingToSave: Boolean get() = items.any { it.status.isSavable }
    val isEmpty: Boolean get() = items.isEmpty() && failures.isEmpty()
}

public data class ImportItem(
    val profile: ConnectionProfile,
    val status: Status,
    val selectedByDefault: Boolean,
) {
    public sealed class Status {
        public abstract val isSavable: Boolean

        public data object New : Status() { override val isSavable: Boolean = true }

        /** Same normalized identity already stored under [existingId]. */
        public data class Duplicate(val existingId: String, val existingName: String) : Status() { override val isSavable: Boolean = false }

        /** Same identity appears earlier in this import (kept only once). */
        public data object DuplicateInBatch : Status() { override val isSavable: Boolean = false }

        /** Parsed fine but the selected core cannot carry it; may still be saved for another core. */
        public data class Unsupported(val reason: UnsupportedReason) : Status() { override val isSavable: Boolean = true }

        public data class Invalid(val errors: List<ConfigError>) : Status() { override val isSavable: Boolean = false }
    }
}
