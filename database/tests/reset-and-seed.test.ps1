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
$resetLauncher = Join-Path $databaseDirectory 'reset-and-seed.bat'
$resetConfigArguments = @('-Config', $configPath)
$fixtureRoot = $null
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
    # The .bat launches Windows PowerShell 5.1; check its propagated exit status too.
    $ErrorActionPreference = 'Continue'
    $output = & $resetLauncher @resetConfigArguments -PsqlPath $PsqlPath -Schema $schema 2>&1
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
    Invoke-Sql "DROP TRIGGER fail_seed ON $schema.users;" | Out-Null

    # Reproduce a checkout in a path containing spaces, launched from a different directory.
    $targetRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $databaseDirectory) 'target'))
    $fixtureRoot = Join-Path $targetRoot ('reset launcher ' + [guid]::NewGuid().ToString('N'))
    $fixtureDatabase = Join-Path $fixtureRoot 'database'
    $fixtureResources = [IO.Path]::Combine($fixtureRoot, 'quiz-server', 'src', 'main', 'resources')
    New-Item -ItemType Directory -Path $fixtureDatabase, $fixtureResources -Force | Out-Null
    foreach ($file in @('reset-and-seed.bat', 'reset-and-seed.ps1', 'seed.sql')) {
        Copy-Item -LiteralPath (Join-Path $databaseDirectory $file) -Destination (Join-Path $fixtureDatabase $file)
    }
    $fixtureLocalConfig = Join-Path $fixtureRoot 'server-local.properties'
    $fixtureResourceConfig = Join-Path $fixtureResources 'server.properties'
    $fixtureExplicitConfig = Join-Path $fixtureRoot 'explicit config with spaces.properties'
    Copy-Item -LiteralPath $configPath -Destination $fixtureLocalConfig
    Copy-Item -LiteralPath $configPath -Destination $fixtureExplicitConfig
    # A bad fallback must not be selected while the local config exists.
    [IO.File]::WriteAllText($fixtureResourceConfig, 'not-valid-properties')
    $resetLauncher = Join-Path $fixtureDatabase 'reset-and-seed.bat'
    $resetConfigArguments = @('-Config', $fixtureExplicitConfig)
    Invoke-Reset $true
    $resetConfigArguments = @()
    Invoke-Reset $true
    Remove-Item -LiteralPath $fixtureLocalConfig -Force
    Copy-Item -LiteralPath $configPath -Destination $fixtureResourceConfig -Force
    Invoke-Reset $true
    Assert-Value '53' (Invoke-Sql "SELECT count(*) FROM $schema.users;") 'Fallback config works from a different directory'

    Invoke-Sql "INSERT INTO $schema.users (username, password_hash, full_name, role) VALUES ('keep_bad_config', 'hash', 'Keep me', 'STUDENT');" | Out-Null
    $resetConfigArguments = @('-Config', (Join-Path $fixtureRoot 'missing.properties'))
    Invoke-Reset $false
    Assert-Value '1' (Invoke-Sql "SELECT count(*) FROM $schema.users WHERE username = 'keep_bad_config';") 'Missing config does not reset data'
    Write-Host 'PASS: batch launcher, paths with spaces, config discovery/fallback, exit codes, reset, and rollback of data/sequences.'
} finally {
    try {
        if ($created) { Invoke-Sql "DROP SCHEMA $schema CASCADE;" | Out-Null }
    } finally {
        try {
            if ($null -ne $fixtureRoot -and (Test-Path -LiteralPath $fixtureRoot)) {
                $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
                if (-not $resolvedFixture.StartsWith($targetRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
                    (Split-Path -Leaf $resolvedFixture) -notmatch '^reset launcher [a-f0-9]{32}$') {
                    throw 'Refusing to remove an unexpected test fixture path.'
                }
                Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
            }
        } finally {
            foreach ($key in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key]) }
        }
    }
}
