# buildRelease.ps1
# Builds all FairPlay branches and collects the JARs in releases/<version>/.
# Run from the project root:
#   .\buildRelease.ps1                        # build only
#   .\buildRelease.ps1 -Version 1.1.0        # explicit version
#   .\buildRelease.ps1 -Upload               # build + upload to Modrinth + CurseForge
#   .\buildRelease.ps1 -Upload -Changelog "Fixed XYZ"

param(
    [string]$Version    = "",
    [switch]$Upload,
    [string]$Changelog  = ""
)

# ── Branch → version suffix mapping ──────────────────────────────────────────
$branches = @(
    [pscustomobject]@{
        Branch           = "master"
        Suffix           = "mc26.2+"
        ModrinthVersions = @("26.2")
        CFVersionIds     = @(16500, 14454)   # 26.2 (type=1), Java 25
    },
    [pscustomobject]@{
        Branch           = "v1214"
        Suffix           = "mc1.21.4-26.1"
        ModrinthVersions = @("1.21.4","1.21.5","1.21.6","1.21.7","1.21.8","1.21.9","1.21.10","1.21.11","26.1","26.1.1","26.1.2")
        CFVersionIds     = @(16118,16119,16120,16121,16124,16125,16126,16127,16128,16129,16130, 11135)  # + Java 21
    },
    [pscustomobject]@{
        Branch           = "v1205"
        Suffix           = "mc1.20.5-1.21.3"
        ModrinthVersions = @("1.20.5","1.20.6","1.21","1.21.1","1.21.2","1.21.3")
        CFVersionIds     = @(11308,11309,11458,16115,16116,16117, 11135)  # + Java 21
    },
    [pscustomobject]@{
        Branch           = "v119"
        Suffix           = "mc1.19-1.20.4"
        ModrinthVersions = @("1.19","1.19.1","1.19.2","1.19.3","1.19.4","1.20","1.20.1","1.20.2","1.20.3","1.20.4")
        CFVersionIds     = @(9189,9260,9551,9552,9792,9970,9993,10864,10865,10866, 8326)  # + Java 17
    },
    [pscustomobject]@{
        Branch           = "v117"
        Suffix           = "mc1.17-1.18.2"
        ModrinthVersions = @("1.17","1.17.1","1.18","1.18.1","1.18.2")
        CFVersionIds     = @(8504,8899, 8326)  # 1.17+1.18 (type=615 only covers majors), Java 17
    }
)

# ── Platform config ───────────────────────────────────────────────────────────
$MODRINTH_PROJECT_ID  = "uoS1aGhp"
$MODRINTH_TOKEN_FILE  = ".modrinth-token"
$CF_PROJECT_ID        = "1507711"
$CF_TOKEN_FILE        = ".curseforge-token"

# ── Build phase ───────────────────────────────────────────────────────────────
$startBranch = git rev-parse --abbrev-ref HEAD
$failed      = @()
$builtJars   = @()

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
        Path             = (Resolve-Path $destPath).Path
        Name             = $destName
        Version          = $releaseVersion
        Suffix           = $b.Suffix
        ModrinthVersions = $b.ModrinthVersions
        CFVersionIds     = $b.CFVersionIds
    }
}

Write-Host "`n=== Restoring branch: $startBranch ===" -ForegroundColor Cyan
git checkout $startBranch 2>&1 | Out-Null

if ($failed.Count -gt 0) {
    Write-Host "`nFailed branches: $($failed -join ', ')" -ForegroundColor Red
}

if (-not $Upload) {
    $rv = if ($Version) { $Version } else { "see above" }
    Write-Host "`nAll JARs in releases/$rv/ — run with -Upload to publish to Modrinth + CurseForge." -ForegroundColor Green
    exit 0
}

Add-Type -AssemblyName System.Net.Http

# ── Modrinth upload ───────────────────────────────────────────────────────────
if (-not (Test-Path $MODRINTH_TOKEN_FILE)) {
    Write-Host "`nMissing $MODRINTH_TOKEN_FILE — skipping Modrinth." -ForegroundColor Yellow
} else {
    $mrToken = (Get-Content $MODRINTH_TOKEN_FILE -Raw).Trim()
    Write-Host "`n=== Uploading to Modrinth ===" -ForegroundColor Cyan

    foreach ($j in $builtJars) {
        Write-Host "  $($j.Name) ..." -NoNewline

        $data = @{
            name           = "FairPlay $($j.Version) ($($j.Suffix))"
            version_number = "$($j.Version)-$($j.Suffix)"
            changelog      = $Changelog
            dependencies   = @()
            game_versions  = $j.ModrinthVersions
            version_type   = "release"
            loaders        = @("paper")
            featured       = $false
            project_id     = $MODRINTH_PROJECT_ID
            file_parts     = @("jarfile")
            primary_file   = "jarfile"
        } | ConvertTo-Json -Compress

        try {
            $client = [System.Net.Http.HttpClient]::new()
            $client.DefaultRequestHeaders.Add("Authorization", $mrToken)
            $client.DefaultRequestHeaders.Add("User-Agent", "flyingfinger1/fairplay-build-script")

            $form = [System.Net.Http.MultipartFormDataContent]::new()
            $form.Add([System.Net.Http.StringContent]::new($data, [System.Text.Encoding]::UTF8, "application/json"), "data")

            $fc = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($j.Path))
            $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new("application/java-archive")
            $form.Add($fc, "jarfile", $j.Name)

            $resp = $client.PostAsync("https://api.modrinth.com/v2/version", $form).Result
            $body = $resp.Content.ReadAsStringAsync().Result

            if ($resp.IsSuccessStatusCode) {
                Write-Host " OK ($( ($body | ConvertFrom-Json).id ))" -ForegroundColor Green
            } else {
                Write-Host " FAILED ($($resp.StatusCode))" -ForegroundColor Red
                Write-Host "    $body" -ForegroundColor DarkRed
            }
        } finally { if ($client) { $client.Dispose() } }
    }
}

# ── CurseForge upload ─────────────────────────────────────────────────────────
if (-not (Test-Path $CF_TOKEN_FILE)) {
    Write-Host "`nMissing $CF_TOKEN_FILE — skipping CurseForge." -ForegroundColor Yellow
} else {
    $cfToken = (Get-Content $CF_TOKEN_FILE -Raw).Trim()
    Write-Host "`n=== Uploading to CurseForge ===" -ForegroundColor Cyan

    foreach ($j in $builtJars) {
        Write-Host "  $($j.Name) ..." -NoNewline

        $cfChangelog = if ($Changelog) { $Changelog } else { "" }
        $metadata = @{
            changelog     = $cfChangelog
            changelogType = "markdown"
            displayName   = "FairPlay $($j.Version) ($($j.Suffix))"
            gameVersions  = $j.CFVersionIds
            releaseType   = "release"
        } | ConvertTo-Json -Compress

        try {
            $client = [System.Net.Http.HttpClient]::new()
            $client.DefaultRequestHeaders.Add("X-Api-Token", $cfToken)

            $form = [System.Net.Http.MultipartFormDataContent]::new()
            $form.Add([System.Net.Http.StringContent]::new($metadata, [System.Text.Encoding]::UTF8, "application/json"), "metadata")

            $fc = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($j.Path))
            $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new("application/java-archive")
            $form.Add($fc, "file", $j.Name)

            $resp = $client.PostAsync("https://minecraft.curseforge.com/api/projects/$CF_PROJECT_ID/upload-file", $form).Result
            $body = $resp.Content.ReadAsStringAsync().Result

            if ($resp.IsSuccessStatusCode) {
                Write-Host " OK ($( ($body | ConvertFrom-Json).id ))" -ForegroundColor Green
            } else {
                Write-Host " FAILED ($($resp.StatusCode))" -ForegroundColor Red
                Write-Host "    $body" -ForegroundColor DarkRed
            }
        } finally { if ($client) { $client.Dispose() } }
    }
}

Write-Host "`nDone." -ForegroundColor Green
Write-Host "  Modrinth: https://modrinth.com/plugin/fairplay-challenge/versions"
Write-Host "  CurseForge: https://www.curseforge.com/minecraft/bukkit-plugins/fairplay-challenge/files"
