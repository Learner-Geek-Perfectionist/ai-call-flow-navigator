param(
    [ValidateSet("all", "codex", "claude")]
    [string]$Target = "all"
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$Source = Join-Path $RepositoryRoot "skills/ai-call-flow-navigator"

function Install-SkillCopy {
    param([Parameter(Mandatory = $true)][string]$DestinationRoot)

    $Destination = Join-Path $DestinationRoot "ai-call-flow-navigator"
    $Stage = Join-Path $DestinationRoot (".ai-call-flow-navigator.install." + [guid]::NewGuid().ToString("N"))
    $Backup = Join-Path $DestinationRoot (".ai-call-flow-navigator.backup." + [guid]::NewGuid().ToString("N"))
    $PreviousMoved = $false

    New-Item -ItemType Directory -Force -Path $DestinationRoot | Out-Null
    try {
        Copy-Item -Path $Source -Destination $Stage -Recurse
        if (Test-Path -LiteralPath $Destination) {
            Move-Item -LiteralPath $Destination -Destination $Backup
            $PreviousMoved = $true
        }
        Move-Item -LiteralPath $Stage -Destination $Destination
        if ($PreviousMoved) {
            Remove-Item -LiteralPath $Backup -Recurse -Force -ErrorAction SilentlyContinue
            $PreviousMoved = $false
        }
    }
    catch {
        if ($PreviousMoved -and -not (Test-Path -LiteralPath $Destination)) {
            Move-Item -LiteralPath $Backup -Destination $Destination
            $PreviousMoved = $false
        }
        throw
    }
    finally {
        if (Test-Path -LiteralPath $Stage) {
            Remove-Item -LiteralPath $Stage -Recurse -Force
        }
    }
    Write-Output "Installed $Destination"
}

switch ($Target) {
    "all" {
        Install-SkillCopy (Join-Path $HOME ".agents/skills")
        Install-SkillCopy (Join-Path $HOME ".claude/skills")
    }
    "codex" {
        Install-SkillCopy (Join-Path $HOME ".agents/skills")
    }
    "claude" {
        Install-SkillCopy (Join-Path $HOME ".claude/skills")
    }
}
