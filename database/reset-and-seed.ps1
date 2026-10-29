<#
.SYNOPSIS
Replace all Online Quiz data in a local database with database/seed.sql.
.EXAMPLE
.\database\reset-and-seed.bat
.EXAMPLE
.\database\reset-and-seed.bat -Config .\server-local.properties -PsqlPath 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
#>
[CmdletBinding()]
param(
    [string]$Config,
    [string]$PsqlPath,
    [ValidatePattern('^[a-zA-Z_][a-zA-Z0-9_]*$')]
    [string]$Schema = 'public'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Config)) {
    $Config = [IO.Path]::Combine($projectRoot, 'server-local.properties')
    if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) {
        $Config = [IO.Path]::Combine($projectRoot, 'quiz-server', 'src', 'main', 'resources', 'server.properties')
    }
}
if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) {
    throw "Configuration file not found: $Config. Run reset-and-seed.bat -Config <path-to-properties>. No data was changed."
}
$configPath = (Resolve-Path -LiteralPath $Config).Path
Write-Host "Using configuration: $configPath"
$properties = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-StringData
foreach ($setting in @(@('db.url', 'DB_URL'), @('db.user', 'DB_USER'), @('db.password', 'DB_PASSWORD'))) {
    $override = [Environment]::GetEnvironmentVariable($setting[1])
    if (-not [string]::IsNullOrWhiteSpace($override)) { $properties[$setting[0]] = $override }
}
$databaseUrl = [string]$properties['db.url']
if ($databaseUrl -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):([0-9]{1,5})/([a-zA-Z_][a-zA-Z0-9_]*)$') {
    throw 'Only a local PostgreSQL JDBC URL with an explicit port and database name is supported.'
}
$databaseHostName = $Matches[1]
$databasePort = [int]$Matches[2]
$databaseName = $Matches[3]
if ($databasePort -lt 1 -or $databasePort -gt 65535) { throw 'Invalid database port.' }
if ([string]::IsNullOrWhiteSpace($properties['db.user'])) {
    throw 'Set db.user in the config or DB_USER.'
}
if ([string]::IsNullOrWhiteSpace($properties['db.password'])) {
    $databasePassword = Read-Host "PostgreSQL password for $($properties['db.user']) at $databaseHostName/$databaseName" -AsSecureString
    $properties['db.password'] = (New-Object Net.NetworkCredential('', $databasePassword)).Password
    if ([string]::IsNullOrWhiteSpace($properties['db.password'])) { throw 'Database password cannot be empty.' }
}

if ([string]::IsNullOrWhiteSpace($PsqlPath)) {
    $psqlCommand = Get-Command psql.exe -ErrorAction SilentlyContinue
    if ($null -ne $psqlCommand) { $PsqlPath = $psqlCommand.Source }
    else {
        $installRoot = Join-Path $env:ProgramFiles 'PostgreSQL'
        $installation = Get-ChildItem -LiteralPath $installRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^\d+(\.\d+)*$' } |
            Sort-Object { [version]($_.Name + '.0') } -Descending |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\psql.exe') -PathType Leaf } |
            Select-Object -First 1
        if ($null -ne $installation) { $PsqlPath = Join-Path $installation.FullName 'bin\psql.exe' }
    }
}
if ([string]::IsNullOrWhiteSpace($PsqlPath) -or -not (Test-Path -LiteralPath $PsqlPath -PathType Leaf)) {
    throw 'psql.exe was not found. Install PostgreSQL or pass -PsqlPath <path-to-psql.exe>. No data was changed.'
}
$PsqlPath = (Resolve-Path -LiteralPath $PsqlPath).Path
$seed = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'seed.sql'), [Text.Encoding]::UTF8)
$beginPattern = '(?m)^BEGIN;[ \t]*\r?$'
$commitPattern = '(?m)^COMMIT;[ \t]*\r?$'
if ([regex]::Matches($seed, $beginPattern).Count -ne 1 -or [regex]::Matches($seed, $commitPattern).Count -ne 1) {
    throw 'Expected exactly one outer BEGIN/COMMIT pair in seed.sql. No data was changed.'
}
# psql owns the transaction. The DO block inside seed.sql is preserved verbatim.
$seed = [regex]::Replace([regex]::Replace($seed, $beginPattern, ''), $commitPattern, '')
$tables = @('users', 'classes', 'class_members', 'questions', 'quizzes', 'quiz_questions',
    'quiz_assignments', 'attempts', 'attempt_answers', 'results', 'anticheat_events')
$qualifiedTables = ($tables | ForEach-Object { '"' + $Schema + '"."' + $_ + '"' }) -join ', '
$sql = @"
SET LOCAL client_encoding TO 'UTF8';
SET LOCAL lock_timeout = '10s';
SET LOCAL search_path TO "$Schema";
TRUNCATE TABLE $qualifiedTables RESTART IDENTITY;
SET LOCAL quiz.allow_demo_seed = 'on';
$seed
"@

$previousPassword = $env:PGPASSWORD
$previousEncoding = $env:PGCLIENTENCODING
$previousConnectTimeout = $env:PGCONNECT_TIMEOUT
$temporarySql = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($temporarySql, $sql, (New-Object Text.UTF8Encoding($false)))
    $env:PGPASSWORD = $properties['db.password']
    $env:PGCLIENTENCODING = 'UTF8'
    $env:PGCONNECT_TIMEOUT = '5'
    Write-Host "Resetting $databaseHostName`:$databasePort/$databaseName, schema $Schema (11 tables)."
    & $PsqlPath -X --no-password -h $databaseHostName -p $databasePort -U $properties['db.user'] `
        -d $databaseName -v ON_ERROR_STOP=1 --single-transaction -f $temporarySql
    if ($LASTEXITCODE -ne 0) {
        throw 'Reset/seed failed. The transaction was rolled back; existing data was preserved.'
    }
    Write-Host 'Reset and seed completed. Demo password: password123 (student50 is disabled).'
} finally {
    $env:PGPASSWORD = $previousPassword
    $env:PGCLIENTENCODING = $previousEncoding
    $env:PGCONNECT_TIMEOUT = $previousConnectTimeout
    Remove-Item -LiteralPath $temporarySql -Force -ErrorAction SilentlyContinue
}
