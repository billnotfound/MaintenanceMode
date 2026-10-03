*Requires CraterLib 3.1.2 or newer*

**Changes**:

- Added support for Minecraft 26.3
- The maintenance message is now shown as a native Minecraft dialog on Minecraft 1.21.6 and newer (including Fabric/Quilt), which allows clickable links. On older versions, the normal kick message is used

**New Features**:

- `useDialog` config option (default `true`) - Show the maintenance message as a dialog instead of a plain kick message
- `dialogTitle` config option - Title of the maintenance dialog
- `dialogLinks` config option - Buttons with clickable links shown in the dialog, each with a `label` and `url`
