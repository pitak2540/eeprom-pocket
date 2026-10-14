; Inno Setup script for EEPROM Pocket (Windows)
#define AppName "EEPROM Pocket"
#define AppVersion "1.1.0"
#define AppExe "EepromPocket.exe"
#ifndef Lite
  #define PubDir "..\publish"
  #define Suffix "Full"
#else
  #define PubDir "..\publish-lite"
  #define Suffix "Lite"
#endif

[Setup]
AppId={{6E4A1C52-8F0B-4C7E-9B7A-3D2F5E1A9C11}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=EEPROM Pocket
DefaultDirName={autopf}\EEPROM Pocket
DefaultGroupName=EEPROM Pocket
DisableProgramGroupPage=yes
OutputDir=Output
OutputBaseFilename=EEPROMPocket-Setup-{#AppVersion}-{#Suffix}
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
Source: "{#PubDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs
Source: "..\firmware\eeprom_bridge\eeprom_bridge.ino"; DestDir: "{app}\firmware\eeprom_bridge"; Flags: ignoreversion
Source: "README-Windows.txt"; DestDir: "{app}"; Flags: ignoreversion isreadme

[Icons]
Name: "{group}\EEPROM Pocket"; Filename: "{app}\{#AppExe}"
Name: "{group}\Arduino Bridge firmware"; Filename: "{app}\firmware\eeprom_bridge"
Name: "{group}\Uninstall EEPROM Pocket"; Filename: "{uninstallexe}"
Name: "{autodesktop}\EEPROM Pocket"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "เปิด EEPROM Pocket"; Flags: nowait postinstall skipifsilent

#ifdef Lite
[Code]
function HasDesktopRuntime8x86(): Boolean;
var
  FindRec: TFindRec;
begin
  Result := FindFirst(ExpandConstant('{commonpf32}\dotnet\shared\Microsoft.WindowsDesktop.App\8.*'), FindRec);
  if Result then FindClose(FindRec);
end;

function InitializeSetup(): Boolean;
var
  ErrCode: Integer;
begin
  Result := True;
  if not HasDesktopRuntime8x86() then
  begin
    if MsgBox('ต้องติดตั้ง .NET 8 Desktop Runtime (x86) ก่อนใช้งาน' + #13#10 +
              'กด Yes เพื่อดาวน์โหลด (ติดตั้งเสร็จแล้วรันตัวติดตั้งนี้อีกครั้ง)' + #13#10 + #13#10 +
              'This program needs the .NET 8 Desktop Runtime (x86). Download it now?',
              mbConfirmation, MB_YESNO) = IDYES then
      ShellExec('open', 'https://aka.ms/dotnet/8.0/windowsdesktop-runtime-win-x86.exe', '', '', SW_SHOWNORMAL, ewNoWait, ErrCode);
    Result := False;
  end;
end;
#endif
