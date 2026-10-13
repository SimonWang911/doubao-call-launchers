function ConvertTo-CallNotificationEvidence {
    param([string]$Dump)
    if ([string]::IsNullOrWhiteSpace($Dump)) { throw 'Notification dump unavailable; cannot infer call absence.' }
    if ($Dump -notmatch '(?m)^[\t ]*(?:Current )?Notification List:') { throw 'Notification dump format unrecognized; cannot infer call status.' }
    $record = '(?m)^[\t ]*NotificationRecord\([^\r\n]*\bpkg=com\.larus\.nova(?=[\t ,])[^\r\n]*\bid=101\b'
    if ([regex]::IsMatch($Dump, $record)) { return 'NotificationRecord(pkg=com.larus.nova id=101)' }
    return 'No Doubao call notification 101.'
}
