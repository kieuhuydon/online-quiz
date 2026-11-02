<#
.SYNOPSIS
    Tự động thiết lập cơ sở dữ liệu PostgreSQL (Database, Schema và Dữ liệu mẫu) cho dự án Online Quiz.
#>
[CmdletBinding()]
param(
    [string]$Config,
    [string]$PsqlPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

Write-Host "=======================================================================" -ForegroundColor Cyan
Write-Host "   ONLINE QUIZ - TU DONG THIET LAP CO SO DU LIEU VA DU LIEU MAU" -ForegroundColor Cyan
Write-Host "=======================================================================" -ForegroundColor Cyan
Write-Host ""

$projectRoot = Split-Path -Parent $PSScriptRoot

# 1. Tìm đường dẫn psql.exe
if ([string]::IsNullOrWhiteSpace($PsqlPath)) {
    $psqlCmd = Get-Command psql.exe -ErrorAction SilentlyContinue
    if ($null -ne $psqlCmd) {
        $PsqlPath = $psqlCmd.Source
    } else {
        $installDirs = @(
            (Join-Path $env:ProgramFiles 'PostgreSQL'),
            (Join-Path ${env:ProgramFiles(x86)} 'PostgreSQL')
        )
        foreach ($dir in $installDirs) {
            if (Test-Path -LiteralPath $dir -PathType Container) {
                $found = Get-ChildItem -LiteralPath $dir -Directory -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -match '^\d+' } |
                    Sort-Object { [int]($_.Name -replace '\D.*', '') } -Descending |
                    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\psql.exe') -PathType Leaf } |
                    Select-Object -First 1
                if ($null -ne $found) {
                    $PsqlPath = Join-Path $found.FullName 'bin\psql.exe'
                    break
                }
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($PsqlPath) -or -not (Test-Path -LiteralPath $PsqlPath -PathType Leaf)) {
    Write-Host "[LOI] Khong tim thay psql.exe tren he thong!" -ForegroundColor Red
    Write-Host "Vui long kiem tra lai xem ban da cai dat PostgreSQL chua hoac them thu muc bin cua PostgreSQL vao bien moi truong PATH." -ForegroundColor Yellow
    exit 1
}

$PsqlPath = (Resolve-Path -LiteralPath $PsqlPath).Path
Write-Host "[1/5] Tim thay PostgreSQL CLI tai: $PsqlPath" -ForegroundColor Green

# 2. Xac dinh file cau hinh
if ([string]::IsNullOrWhiteSpace($Config)) {
    $localConfig = Join-Path $projectRoot 'server-local.properties'
    $serverConfig = Join-Path $projectRoot 'quiz-server\src\main\resources\server.properties'
    $serverExample = Join-Path $projectRoot 'quiz-server\src\main\resources\server.properties.example'

    if (Test-Path -LiteralPath $localConfig -PathType Leaf) {
        $Config = $localConfig
    } elseif (Test-Path -LiteralPath $serverConfig -PathType Leaf) {
        $Config = $serverConfig
    } elseif (Test-Path -LiteralPath $serverExample -PathType Leaf) {
        Copy-Item -LiteralPath $serverExample -Destination $serverConfig
        $Config = $serverConfig
        Write-Host "Da khoi tao file cau hinh: $serverConfig" -ForegroundColor Yellow
    } else {
        Write-Host "[LOI] Khong tim thay file cau hinh server.properties!" -ForegroundColor Red
        exit 1
    }
}

$configPath = (Resolve-Path -LiteralPath $Config).Path
Write-Host "[2/5] Su dung cau hinh tai: $configPath" -ForegroundColor Green

# Doc file properties
$propData = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-StringData
$dbUrl = [string]$propData['db.url']
$dbUser = [string]$propData['db.user']
$dbPassword = [string]$propData['db.password']

# Override bang bien moi truong neu co
if (-not [string]::IsNullOrWhiteSpace($env:DB_URL)) { $dbUrl = $env:DB_URL }
if (-not [string]::IsNullOrWhiteSpace($env:DB_USER)) { $dbUser = $env:DB_USER }
if (-not [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) { $dbPassword = $env:DB_PASSWORD }

# Phan tich db.url: jdbc:postgresql://host:port/dbname
$hostName = 'localhost'
$port = 5432
$dbName = 'quizdb'
if ($dbUrl -match '^jdbc:postgresql://([^:/]+):([0-9]+)/([^?]+)') {
    $hostName = $Matches[1]
    $port = [int]$Matches[2]
    $dbName = $Matches[3]
}

if ([string]::IsNullOrWhiteSpace($dbUser)) { $dbUser = 'postgres' }

if ([string]::IsNullOrWhiteSpace($dbPassword) -or $dbPassword -eq 'your_db_password_here' -or $dbPassword -eq 'your_password_here') {
    $secure = Read-Host "Nhap mat khau PostgreSQL cho user '$dbUser' ($hostName`:$port)" -AsSecureString
    $dbPassword = (New-Object Net.NetworkCredential('', $secure)).Password
    if ([string]::IsNullOrWhiteSpace($dbPassword)) {
        Write-Host "[LOI] Mat khau khong duoc de trong!" -ForegroundColor Red
        exit 1
    }
}

$env:PGPASSWORD = $dbPassword
$env:PGCLIENTENCODING = 'UTF8'
$env:PGCONNECT_TIMEOUT = '10'

# 3. Kiem tra va tao Database neu chua co
Write-Host "[3/5] Kiem tra co so du lieu '$dbName' tren $hostName`:$port..." -ForegroundColor Yellow

$dbCheckQuery = "SELECT 1 FROM pg_database WHERE datname = '$dbName';"
$dbExists = & $PsqlPath -X --no-password -h $hostName -p $port -U $dbUser -d postgres -t -c $dbCheckQuery 2>$null
if ($LASTEXITCODE -ne 0) {
    # Neu khong vao duoc postgres db, thu vao thang $dbName
    $testDirect = & $PsqlPath -X --no-password -h $hostName -p $port -U $dbUser -d $dbName -t -c "SELECT 1;" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[LOI] Khong the ket noi den PostgreSQL voi user '$dbUser' tai $hostName`:$port!" -ForegroundColor Red
        Write-Host "Vui long kiem tra lai: PostgreSQL service dang bat va mat khau chinh xac." -ForegroundColor Yellow
        exit 1
    }
    $dbExists = "1"
}

if ([string]::IsNullOrWhiteSpace($dbExists) -or $dbExists.Trim() -ne "1") {
    Write-Host "  -> Database '$dbName' chua ton tai. Dang tao database moi..." -ForegroundColor Yellow
    & $PsqlPath -X --no-password -h $hostName -p $port -U $dbUser -d postgres -c "CREATE DATABASE $dbName;"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[LOI] Khong the tao database '$dbName'!" -ForegroundColor Red
        exit 1
    }
    Write-Host "  -> Da tao database '$dbName' thanh cong." -ForegroundColor Green
} else {
    Write-Host "  -> Database '$dbName' da san sang." -ForegroundColor Green
}

# 4. Khoi tao Schema (database/schema.sql)
Write-Host "[4/5] Khoi tao cau truc bang (Schema) cho '$dbName'..." -ForegroundColor Yellow
$schemaFile = Join-Path $projectRoot 'database\schema.sql'
if (-not (Test-Path -LiteralPath $schemaFile -PathType Leaf)) {
    Write-Host "[LOI] Khong tim thay file $schemaFile!" -ForegroundColor Red
    exit 1
}

# Don sach schema cu truoc khi tao moi de tranh xung dot neu chay lai
$cleanSql = @"
SET client_min_messages = warning;
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO public;
"@
$tempClean = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($tempClean, $cleanSql, [Text.Encoding]::UTF8)
    & $PsqlPath -X --no-password -h $hostName -p $port -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -f $tempClean 2>$null
} finally {
    Remove-Item -LiteralPath $tempClean -Force -ErrorAction SilentlyContinue
}

# Chay schema.sql
& $PsqlPath -X --no-password -h $hostName -p $port -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -v client_min_messages=warning -f $schemaFile 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[LOI] Thuc thi schema.sql that bai!" -ForegroundColor Red
    exit 1
}
Write-Host "  -> Da tao day du 11 bang, rang buoc va chi muc (Indexes) thanh cong." -ForegroundColor Green

# 5. Nap du lieu mau (database/seed.sql)
Write-Host "[5/5] Nap du lieu mau (Accounts, Classes, Questions, Quizzes)..." -ForegroundColor Yellow
$seedFile = Join-Path $projectRoot 'database\seed.sql'
if (-not (Test-Path -LiteralPath $seedFile -PathType Leaf)) {
    Write-Host "[LOI] Khong tim thay file $seedFile!" -ForegroundColor Red
    exit 1
}

$seedContent = [IO.File]::ReadAllText($seedFile, [Text.Encoding]::UTF8)
$seedSql = "SET client_min_messages = warning;`nSET quiz.allow_demo_seed = 'on';`n" + $seedContent
$tempSeed = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($tempSeed, $seedSql, [Text.Encoding]::UTF8)
    & $PsqlPath -X --no-password -h $hostName -p $port -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -v client_min_messages=warning -f $tempSeed 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[LOI] Nap seed.sql that bai!" -ForegroundColor Red
        exit 1
    }
} finally {
    Remove-Item -LiteralPath $tempSeed -Force -ErrorAction SilentlyContinue
}

Write-Host "  -> Da nap toan bo du lieu mau thanh cong." -ForegroundColor Green
Write-Host ""
Write-Host "=======================================================================" -ForegroundColor Cyan
Write-Host "             THIET LAP DATABASE HOAN TAT XUAT SAC!                    " -ForegroundColor Green
Write-Host "=======================================================================" -ForegroundColor Cyan
Write-Host "Database: $dbName ($hostName`:$port)" -ForegroundColor White
Write-Host "User:     $dbUser" -ForegroundColor White
Write-Host ""
Write-Host "CAC TAI KHOAN SAN SANG SU DUNG (Mat khau chung: password123):" -ForegroundColor Yellow
Write-Host "  - Quan tri vien (Admin):    admin" -ForegroundColor White
Write-Host "  - Giao vien (Teacher):      teacher1, teacher2" -ForegroundColor White
Write-Host "  - Hoc vien (Student):       student1, student2, ... student49" -ForegroundColor White
Write-Host ""
Write-Host "CAC BUOC TIEP THEO DE CHAY DU AN:" -ForegroundColor Yellow
Write-Host "  1. Chay file 'run-server.bat' de khoi dong Backend Server." -ForegroundColor White
Write-Host "  2. Chay file 'run-client.bat' de mo ung dung Client tren man hinh." -ForegroundColor White
Write-Host "=======================================================================" -ForegroundColor Cyan
