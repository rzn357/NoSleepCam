# Show a Windows toast when Claude Code needs attention.
# Reusable across hook events (Notification, PreToolUse:AskUserQuestion/ExitPlanMode, Stop, ...).
# Reads the hook payload (JSON) from stdin to pick an appropriate message.
# Bypasses BurntToast 1.1.0 (hangs on default AppId, no -AppId support) via direct WinRT.
$ErrorActionPreference = 'SilentlyContinue'

# Read the hook payload from stdin (only when redirected, to avoid blocking)
$payload = $null
if ([Console]::IsInputRedirected) {
    $raw = [Console]::In.ReadToEnd()
    if ($raw) { try { $payload = $raw | ConvertFrom-Json } catch {} }
}

$event = [string]$payload.hook_event_name
$tool  = [string]$payload.tool_name

# Pick a message based on the event / tool
switch -Regex ("$event/$tool") {
    'AskUserQuestion'    { $line = 'Claude ставить запитання'; break }
    'ExitPlanMode'       { $line = 'Claude пропонує план на затвердження'; break }
    '^PermissionRequest' { $line = if ($tool) { "Claude просить дозвіл: $tool" } else { 'Claude просить дозвіл' }; break }
    '^Notification'      { $line = 'Claude потребує підтвердження'; break }
    '^Stop'           { $line = 'Claude завершив відповідь'; break }
    default {
        if ($payload.message) { $line = [string]$payload.message }
        else { $line = 'Claude потребує уваги' }
    }
}

# Register an AppUserModelID so Windows will actually display the toast
$AppId = 'ClaudeCode'
$key = "HKCU:\Software\Classes\AppUserModelId\$AppId"
if (-not (Test-Path $key)) {
    New-Item -Path $key -Force | Out-Null
    New-ItemProperty -Path $key -Name DisplayName -Value 'Claude Code' -PropertyType String -Force | Out-Null
}

[void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime]
[void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime]

$xmlLine = [System.Security.SecurityElement]::Escape($line)
$doc = [Windows.Data.Xml.Dom.XmlDocument]::new()
$doc.LoadXml(@"
<toast>
  <visual>
    <binding template="ToastText02">
      <text id="1">ClaudeCode</text>
      <text id="2">$xmlLine</text>
    </binding>
  </visual>
  <audio src="ms-winsoundevent:Notification.Default"/>
</toast>
"@)

$toast = [Windows.UI.Notifications.ToastNotification]::new($doc)
# Unique Tag/Group so Windows treats each notification as distinct (no dedup/suppression)
$toast.Tag = ([string][System.Guid]::NewGuid()).Substring(0, 16)
$toast.Group = 'ClaudeCode'
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($AppId).Show($toast)