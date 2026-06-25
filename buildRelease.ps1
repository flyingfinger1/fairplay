# buildRelease.ps1
# Builds all FairPlay branches and collects the JARs in releases/<version>/.
# Run from the project root:
#   .\buildRelease.ps1                        # build only
#   .\buildRelease.ps1 -Version 1.1.0        # explicit version
#   .\buildRelease.ps1 -Upload               # build + upload to Modrinth
#   .\buildRelease.ps1 -Upload -Changelog "Fixed XYZ"

param(
    [string]$Version    = "",
    [switch]$Upload,
    [string]$Changelog  = ""
)

# ── Branch → MC version suffix mapping ───────────────────────────────────────
$branches = @(
    [pscustomobject]@{
        Branch      = "master"
        Suffix      = "mc26.2+"
        GameVersions = @("26.2")
    },
    [pscustomobject]@{
        Branch      = "v1214"
        Suffix      = "mc1.21.4-26.1"
        GameVersions = @("1.21.4","1.21.5","1.21.6","1.21.7","1.21.8","1.21.9","1.21.10","1.21.11","26.1","26.1.1","26.1.2")
    },
    [pscustomobject]@{
        Branch      = "v1205"
        Suffix      = "mc1.20.5-1.21.3"
        GameVersions = @("1.20.5","1.20.6","1.21","1.21.1","1.21.2","1.21.3")
    },
    [pscustomobject]@{
        Branch      = "v119"
        Suffix      = "mc1.19-1.20.4"
        GameVersions = @("1.19","1.19.1","1.19.2","1.19.3","1.19.4","1.20","1.20.1","1.20.2","1.20.3","1.20.4")
    },
    [pscustomobject]@{
        Branch      = "v117"
        Suffix      = "mc1.17-1.18.2"
        GameVersions = @("1.17","1.17.1","1.18","1.18.1","1.18.2")
    }
)

# ── Modrinth config ───────────────────────────────────────────────────────────
$MODRINTH_PROJECT_ID = "uoS1aGhp"
$TOKEN_FILE          = ".modrinth-token"

# ── Build phase ───────────────────────────────────────────────────────────────
$startBranch   = git rev-parse --abbrev-ref HEAD
$failed        = @()
$builtJars     = @()   # collect for upload phase

foreach ($b in $branches) {
    Write-Host "`n=== $($b.Branch) ===" -ForegroundColor Cyan
    git checkout $b.Branch 2>&1 | Out-Null

    gradle build 2>&1 | Select-String -Pattern "BUILD|error:|Error" | ForEach-Object { Write-Host $_ }

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  BUILD FAILED" -ForegroundColor Red
        $failed += $b.Branch
        continue
    }

    $jar = Get-ChildItem "build/libs/FairPlay-*.jar" |
           Where-Object { $_.Name -notlike "*-sources*" } |
           Select-Object -First 1
    if (-not $jar) {
        Write-Host "  No JAR found" -ForegroundColor Yellow
        $failed += $b.Branch
        continue
    }

    $detectedVersion = $jar.BaseName -replace "^FairPlay-", ""
    $releaseVersion  = if ($Version) { $Version } else { $detectedVersion }
    $releaseDir      = "releases/$releaseVersion"
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

    $destName = "FairPlay-$releaseVersion-$($b.Suffix).jar"
    $destPath = "$releaseDir/$destName"
    Copy-Item $jar.FullName -Destination $destPath -Force
    Write-Host "  -> $destPath" -ForegroundColor Green

    $builtJars += [pscustomobject]@{
        Path         = (Resolve-Path $destPath).Path
        Name         = $destName
        Version      = $releaseVersion
        Suffix       = $b.Suffix
        GameVersions = $b.GameVersions
    }
}

Write-Host "`n=== Restoring branch: $startBranch ===" -ForegroundColor Cyan
git checkout $startBranch 2>&1 | Out-Null

if ($failed.Count -gt 0) {
    Write-Host "`nFailed branches: $($failed -join ', ')" -ForegroundColor Red
}

# ── Upload phase (only with -Upload flag) ─────────────────────────────────────
if (-not $Upload) {
    $rv = if ($Version) { $Version } else { "see above" }
    Write-Host "`nAll JARs in releases/$rv/ — run with -Upload to publish to Modrinth." -ForegroundColor Green
    exit 0
}

if (-not (Test-Path $TOKEN_FILE)) {
    Write-Host "`nMissing $TOKEN_FILE — create it with your Modrinth token." -ForegroundColor Red
    exit 1
}
$token = (Get-Content $TOKEN_FILE -Raw).Trim()

Write-Host "`n=== Uploading to Modrinth ===" -ForegroundColor Cyan

Add-Type -AssemblyName System.Net.Http

foreach ($j in $builtJars) {
    Write-Host "  Uploading $($j.Name) ..." -NoNewline

    $data = @{
        name          = "FairPlay $($j.Version) ($($j.Suffix))"
        version_number = "$($j.Version)-$($j.Suffix)"
        changelog     = $Changelog
        dependencies  = @()
        game_versions = $j.GameVersions
        version_type  = "release"
        loaders       = @("paper")
        featured      = $false
        project_id    = $MODRINTH_PROJECT_ID
        file_parts    = @("jarfile")
        primary_file  = "jarfile"
    } | ConvertTo-Json -Compress

    try {
        $client = [System.Net.Http.HttpClient]::new()
        $client.DefaultRequestHeaders.Add("Authorization", $token)
        $client.DefaultRequestHeaders.Add("User-Agent", "flyingfinger1/fairplay-build-script")

        $form = [System.Net.Http.MultipartFormDataContent]::new()

        $jsonContent = [System.Net.Http.StringContent]::new($data, [System.Text.Encoding]::UTF8, "application/json")
        $form.Add($jsonContent, "data")

        $fileBytes   = [System.IO.File]::ReadAllBytes($j.Path)
        $fileContent = [System.Net.Http.ByteArrayContent]::new($fileBytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new("application/java-archive")
        $form.Add($fileContent, "jarfile", $j.Name)

        $response = $client.PostAsync("https://api.modrinth.com/v2/version", $form).Result
        $body     = $response.Content.ReadAsStringAsync().Result

        if ($response.IsSuccessStatusCode) {
            $id = ($body | ConvertFrom-Json).id
            Write-Host " OK ($id)" -ForegroundColor Green
        } else {
            Write-Host " FAILED ($($response.StatusCode))" -ForegroundColor Red
            Write-Host "    $body" -ForegroundColor DarkRed
        }
    } finally {
        if ($client) { $client.Dispose() }
    }
}

Write-Host "`nDone. Check https://modrinth.com/plugin/fairplay-challenge/versions" -ForegroundColor Green
