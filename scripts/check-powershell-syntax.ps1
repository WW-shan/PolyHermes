$ErrorActionPreference = "Stop"
$scriptRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "."))
$files = Get-ChildItem -LiteralPath $scriptRoot -Filter "*.ps1" -File
$failures = @()
foreach ($file in $files) {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count -gt 0) {
        $failures += [pscustomobject]@{
            file = $file.Name
            errors = ($errors | ForEach-Object { $_.Message }) -join "; "
        }
    }
}
if ($failures.Count -gt 0) {
    $failures | Format-Table -AutoSize -Wrap | Out-String | Write-Output
    exit 1
}
Write-Output "PowerShell syntax OK: $($files.Count) scripts"