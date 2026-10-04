*Requires CraterLib 3.1.2 or newer*

**Changes**:

- Added support for Minecraft 26.3
- The maintenance message is now shown as a native Minecraft dialog on Minecraft 1.21.6 and newer (including Fabric/Quilt), which allows clickable links. On older versions, the normal kick message is used
- Closing the dialog (or the dialogTimeout) releases the configuration and kicks the player right before they would enter the world, so the client leaves the server cleanly

**Bug Fixes**:

- Fixed the player not actually being disconnected when closing the dialog or when the dialog timeout expired (the disconnect call crashed with a ClassCastException)
- Fixed the client getting stuck on the joining screen when the player was disconnected during the configuration phase

**New Features**:

- `useDialog` config option (default `true`) - Show the maintenance message as a dialog instead of a plain kick message
- `dialogTitle` config option - Title of the maintenance dialog
- `dialogLinks` config option - Buttons with clickable links shown in the dialog, each with a `label` and `url`
- `dialogTimeout` config option (default `30` seconds) - Disconnect players that keep the dialog open for too long. Set to `0` to hold the connection until the dialog is closed
