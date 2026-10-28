# Integration check: uses only a uniquely named schema in local quizdb_test, then removes it.
[CmdletBinding()]
param(
    [string]$Config = (Join-Path $PSScriptRoot '..\..\db-test-local.properties'),
    [string]$PsqlPath = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$configPath = (Resolve-Path -LiteralPath $Config).Path
$settings = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-StringData
if ($settings['db.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):([0-9]{1,5})/quizdb_test$' -or $settings['db.user'] -ne 'quiz_test') {
    throw 'This test requires a local quizdb_test database and quiz_test role.'
}
$testHostName = $Matches[1]
$testPort = $Matches[2]
$schema = 'seed_reset_' + [guid]::NewGuid().ToString('N')
$databaseDirectory = Split-Path -Parent $PSScriptRoot
$savedEnvironment = @{}
foreach ($key in @('PGPASSWORD', 'PGCLIENTENCODING', 'PGCONNECT_TIMEOUT', 'DB_URL', 'DB_USER', 'DB_PASSWORD')) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key)
}

function Invoke-Sql([string]$Sql) {
    $sqlFile = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($sqlFile, $Sql, (New-Object Text.UTF8Encoding($false)))
        $output = & $PsqlPath -X --no-password -h $testHostName -p $testPort -U quiz_test -d quizdb_test `
            -v ON_ERROR_STOP=1 -t -A -f $sqlFile
        if ($LASTEXITCODE -ne 0) { throw 'Test fixture SQL failed.' }
        return ($output -join "`n").Trim()
    } finally { Remove-Item -LiteralPath $sqlFile -Force -ErrorAction SilentlyContinue }
}

function Assert-Value([string]$Expected, [string]$Actual, [string]$Description) {
    if ($Expected -cne $Actual) { throw "$Description : expected '$Expected', got '$Actual'." }
}

function Invoke-Reset([bool]$ExpectSuccess) {
    # Exercise the actual script in Windows PowerShell 5.1, including native process failures.
    $ErrorActionPreference = 'Continue'
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $databaseDirectory 'reset-and-seed.ps1') `
        -Config $configPath -PsqlPath $PsqlPath -Schema $schema 2>&1
    $code = $LASTEXITCODE
    if (($code -eq 0) -ne $ExpectSuccess) { throw "Unexpected reset exit code $code : $($output -join ' ')" }
}

$created = $false
try {
    $env:PGPASSWORD = $settings['db.password']
    $env:PGCLIENTENCODING = 'UTF8'
    $env:PGCONNECT_TIMEOUT = '5'
    $env:DB_URL = $settings['db.url']
    $env:DB_USER = $settings['db.user']
    $env:DB_PASSWORD = $settings['db.password']
    Assert-Value 'quizdb_test|quiz_test' (Invoke-Sql "SELECT current_database() || '|' || current_user;") 'Database identity'
    Invoke-Sql "CREATE SCHEMA $schema;" | Out-Null
    $created = $true
    $schemaSql = [IO.File]::ReadAllText((Join-Path $databaseDirectory 'schema.sql'))
    Invoke-Sql "SET search_path TO $schema;`n$schemaSql" | Out-Null
    Invoke-Sql "INSERT INTO $schema.users (username, password_hash, full_name, role) VALUES ('old_fixture', 'hash', 'Old data', 'STUDENT');" | Out-Null

    Invoke-Reset $true
    Assert-Value '53|45|8|0' (Invoke-Sql @"
SELECT (SELECT count(*) FROM $schema.users) || '|' || (SELECT count(*) FROM $schema.class_members)
    || '|' || (SELECT count(*) FROM $schema.results)
    || '|' || (SELECT count(*) FROM $schema.users WHERE username = 'old_fixture');
"@) 'Old data replaced by demo seed'
    Assert-Value '1' (Invoke-Sql "SELECT id FROM $schema.users WHERE username = 'admin';") 'Identity restarted'
    Invoke-Reset $true
    Assert-Value '53' (Invoke-Sql "SELECT count(*) FROM $schema.users;") 'Repeated reset does not duplicate accounts'

    # This trigger survives TRUNCATE and forces a seed error after the reset starts.
    Invoke-Sql @'
INSERT INTO __SCHEMA__.users (username, password_hash, full_name, role) VALUES ('keep_on_failure', 'hash', 'Keep me', 'STUDENT');
CREATE FUNCTION __SCHEMA__.reject_demo_admin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.username = 'admin' THEN RAISE EXCEPTION 'Intentional seed test failure'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER fail_seed BEFORE INSERT ON __SCHEMA__.users FOR EACH ROW EXECUTE FUNCTION __SCHEMA__.reject_demo_admin();
'@.Replace('__SCHEMA__', $schema) | Out-Null
    $sequenceBefore = Invoke-Sql "SELECT last_value FROM $schema.users_id_seq;"
    Invoke-Reset $false
    Assert-Value '54|1|8' (Invoke-Sql @"
SELECT (SELECT count(*) FROM $schema.users) || '|' ||
    (SELECT count(*) FROM $schema.users WHERE username = 'keep_on_failure') || '|' ||
    (SELECT count(*) FROM $schema.results);
"@) 'Failed seed restores the old accounts and results'
    Assert-Value $sequenceBefore (Invoke-Sql "SELECT last_value FROM $schema.users_id_seq;") 'Failed seed restores the identity sequence'
    Write-Host 'PASS: replacement, identity reset, repeated reset, and rollback of data/sequences on seed failure.'
} finally {
    try {
        if ($created) { Invoke-Sql "DROP SCHEMA $schema CASCADE;" | Out-Null }
    } finally {
        foreach ($key in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key]) }
    }
}
