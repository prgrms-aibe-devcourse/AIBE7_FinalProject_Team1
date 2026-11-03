from pathlib import Path
import xml.etree.ElementTree as ET
import sys

required = {
    "m1FreshDatedAndForeignKeysRejectOrphans",
    "m2DemoRetainsItsValidOwners",
    "m3ExistingDatedDataIsPreserved",
    "m4PendingChainIsSeparateAndStrictlyResolved",
    "m5SnapshotPatternNormalizesPurgesAndConverges",
    "m6NormalizationFailureRollsBackEarlierOwnershipChanges",
    "m6PurgeRequiresExactApprovalAndDetectsTargetContentDrift",
    "m6UnknownTablesAndMidDeleteFailureRollBack",
    "m6MissingParentColumnIsReportedAsNotRun",
    "m6CatalogAndTypeGuardsRejectTampering",
    "m6HistoryIdentityAndIgnorePoliciesAreMeasured",
    "m6NormalizationRejectsPartialAndWrongCatalog",
    "purgePreservesUsersProfileBytesAndHistoryOnSuccess",
    "cliOperationApprovalsRejectCrossPurposeAndMalformedPlans",
}
cases = [
    case
    for report in Path("build/test-results/test").glob("TEST-*.xml")
    for case in ET.parse(report).getroot().iter("testcase")
    if case.get("classname", "").endswith(".DbConvergencePostgresTest")
]
for name in sorted(required if "--api-only" not in sys.argv else set()):
    matches = [case for case in cases if case.get("name") == name + "()"]
    if len(matches) != 1 or any(
        matches[0].find(tag) is not None for tag in ("skipped", "failure", "error")
    ):
        raise SystemExit(f"Convergence test must execute exactly once and pass: {name}")
if "--api-only" not in sys.argv:
    print(f"convergence: tests={len(required)} failures=0 errors=0 skipped=0 (JUnit XML)")
api_cases = [
    case
    for report in Path("build/test-results/test").glob("TEST-*.xml")
    for case in ET.parse(report).getroot().iter("testcase")
    if case.get("classname", "").endswith(".InternalTradeOwnershipHttpPostgresTest")
    and case.get("name") == "convergenceApiCreatesEncryptedProductDocumentsImagesAndProfile()"
]
if len(api_cases) != 1 or any(
    api_cases[0].find(tag) is not None for tag in ("skipped", "failure", "error")
):
    raise SystemExit("Convergence API smoke must execute exactly once and pass")
print("convergence API smoke: tests=1 failures=0 errors=0 skipped=0 (JUnit XML)")
