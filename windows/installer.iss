; Inno Setup script for EEPROM Pocket (Windows)
#define AppName "EEPROM Pocket"
#define AppVersion "1.1.0"
#define AppExe "EepromPocket.exe"

[Setup]
AppId={{6E4A1C52-8F0B-4C7E-9B7A-3D2F5E1A9C11}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=EEPROM Pocket
DefaultDirName={autopf}\EEPROM Pocket
DefaultGroupName=EEPROM Pocket
DisableProgramGroupPage=yes
OutputDir=Output
OutputBaseFilename=EEPROMPocket-Setup-{#AppVersion}
SetupIconFile=EepromPocket.Win\app.ico
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "สร้างไอคอนบนเดสก์ท็อป (Create a desktop icon)"; GroupDescription: "ไอคอนเพิ่มเติม:"

[Files]
Source: "..\publish\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\firmware\eeprom_bridge\eeprom_bridge.ino"; DestDir: "{app}\firmware\eeprom_bridge"; Flags: ignoreversion
Source: "README-Windows.txt"; DestDir: "{app}"; Flags: ignoreversion isreadme

[Icons]
Name: "{group}\EEPROM Pocket"; Filename: "{app}\{#AppExe}"
Name: "{group}\Arduino Bridge firmware"; Filename: "{app}\firmware\eeprom_bridge"
Name: "{group}\Uninstall EEPROM Pocket"; Filename: "{uninstallexe}"
Name: "{autodesktop}\EEPROM Pocket"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "เปิด EEPROM Pocket"; Flags: nowait postinstall skipifsilent
