# Election Contact Directory - Offline Master Embedded

This build embeds the supplied master Excel file inside the APK assets and also includes a normalized JSON seed generated from its `Directory` worksheet.

## Embedded master
- Source Excel: Election_Contact_Directory_Master_Merged_App_Ready(1).xlsx
- Directory records: 499
- Supporting sheets remain in the embedded Excel for reference.
- Runtime initialization uses `directory.json` so the app does not need an Excel parser just to start.

## First launch
The app creates its local Room database and automatically seeds all 499 directory records from the bundled JSON. No Excel import is required on each phone.

## Offline operation
After first launch, search, filters, staff details, favorites, offices, birthdays and other directory features work from the local database without internet access.

## Administrator import
Excel/JSON import remains available from Settings and is protected by the administrator login. Imported data replaces the current local directory on that device.

## Future server synchronization
The local database and seed architecture can later be connected to a central ASP.NET Core/SQL Server service without requiring the app to be redesigned around online-only operation.

## FIXED3 build optimization
- Removed unused `androidx.compose.material:material-icons-extended` dependency; the app only uses `Icons.Default`.
- Increased Gradle heap to 4 GB.
- Limited Gradle workers to 2 for stability on 16 GB RAM PCs.
- Enabled Gradle build cache/parallelism.


FIXED5: Aligns JavaCompile and KotlinCompile JVM targets to Java 17 to prevent inconsistent JVM-target validation failures.
