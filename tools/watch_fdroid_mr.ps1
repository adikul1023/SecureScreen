param(
    [string]$Project = "fdroid%2Ffdroiddata",
    [int]$MergeRequest = 36671,
    [int]$IntervalSeconds = 60,
    [switch]$Once,
    [string]$EnvFile = ""
)

function Get-GitLabTokenFromEnvFile {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }

    foreach ($line in (Get-Content -LiteralPath $Path)) {
        if ($line -match '^\s*#') {
            continue
        }

        if ($line -match '^\s*GITLAB_TOKEN\s*=\s*(.+?)\s*$') {
            $value = $Matches[1].Trim()

            if (
                ($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'"))
            ) {
                $value = $value.Substring(1, $value.Length - 2)
            }

            return $value
        }
    }

    return $null
}

if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path (Split-Path -Parent $PSScriptRoot) ".env"
}

$uri = "https://gitlab.com/api/v4/projects/$Project/merge_requests/$MergeRequest/notes?per_page=100&sort=asc"
$envToken = $env:GITLAB_TOKEN
$fileToken = Get-GitLabTokenFromEnvFile -Path $EnvFile
$token = $fileToken

if (-not $token) {
    $token = $envToken
}

function Build-Headers {
    param([string]$AccessToken)

    $h = @{}
    if ($AccessToken) {
        $h["PRIVATE-TOKEN"] = $AccessToken
    }
    return $h
}

$seen = @{}

while ($true) {
    try {
        $headers = Build-Headers -AccessToken $token
        $notes = Invoke-RestMethod -Headers $headers -Uri $uri -ErrorAction Stop
    }
    catch {
        $failedWith401 = $_.Exception.Message -match '401'
        $alternateToken = $null

        if ($token -eq $fileToken) {
            $alternateToken = $envToken
        } else {
            $alternateToken = $fileToken
        }

        $canRetryWithAlternate =
            $failedWith401 -and
            -not [string]::IsNullOrWhiteSpace($alternateToken) -and
            $alternateToken -ne $token

        if ($canRetryWithAlternate) {
            $token = $alternateToken
            Write-Host "Primary token failed with 401. Retrying with alternate token source..."
            continue
        }

        Write-Host "Failed to read MR notes: $($_.Exception.Message)"
        Write-Host 'Tip: set a token first: $env:GITLAB_TOKEN="<your_gitlab_token>"'
        Write-Host "Or add GITLAB_TOKEN to: $EnvFile"
        break
    }

    foreach ($n in ($notes | Sort-Object id)) {
        if (-not $seen.ContainsKey($n.id)) {
            $seen[$n.id] = $true
            $time = ([datetime]$n.created_at).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss")
            $body = ($n.body -replace "`r?`n", " ")
            Write-Host "[$time] @$($n.author.username): $body"
        }
    }

    if ($Once) {
        break
    }

    Start-Sleep -Seconds $IntervalSeconds
}
