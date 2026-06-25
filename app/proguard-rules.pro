# This app uses no reflection, JSON serialization, Parcelable, or @Keep.
# Manifest-referenced components (3 activities + AppMonitorService) are kept by
# AGP's manifest-derived rules. Material/AppCompat/RecyclerView ship their own
# consumer rules. AppStat is in-memory only and may be renamed/inlined freely.
# No app-specific keep rules are required.
