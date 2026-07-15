param(
    [ValidateSet("all", "codex", "claude")]
    [string]$Target = "all"
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$Source = Join-Path $RepositoryRoot "skills/ai-call-flow-navigator"

function Set-ClaudeExplicitInvocationMetadata {
    param([Parameter(Mandatory = $true)][string]$Stage)

    $SkillFile = Join-Path $Stage "SKILL.md"
    $TemporarySkillFile = Join-Path $Stage (".SKILL.md.claude." + [guid]::NewGuid().ToString("N"))
    $Utf8 = [System.Text.UTF8Encoding]::new($false, $true)

    try {
        $Content = [System.IO.File]::ReadAllText($SkillFile, $Utf8)
        $Delimiters = [regex]::Matches($Content, '(?m)^---[ \t]*\r?$')
        if ($Delimiters.Count -lt 2 -or $Delimiters[0].Index -ne 0) {
            throw "SKILL.md does not contain valid YAML frontmatter delimiters."
        }

        $Frontmatter = $Content.Substring(0, $Delimiters[1].Index)
        if ($Frontmatter -match '(?m)^(disable-model-invocation|argument-hint)[ \t]*:') {
            throw "The canonical SKILL.md contains Claude-only invocation metadata."
        }

        $FirstNewLineIndex = $Content.IndexOf("`n")
        if ($FirstNewLineIndex -lt 0) {
            throw "SKILL.md frontmatter does not contain a line ending."
        }
        if ($FirstNewLineIndex -gt 0 -and $Content[$FirstNewLineIndex - 1] -eq "`r") {
            $NewLine = "`r`n"
        }
        else {
            $NewLine = "`n"
        }
        $Insertion = "disable-model-invocation: true${NewLine}argument-hint: `"<topic>`"${NewLine}"
        $ClaudeContent = $Content.Insert($Delimiters[1].Index, $Insertion)
        [System.IO.File]::WriteAllText($TemporarySkillFile, $ClaudeContent, $Utf8)
        Move-Item -LiteralPath $TemporarySkillFile -Destination $SkillFile -Force
    }
    finally {
        if (Test-Path -LiteralPath $TemporarySkillFile) {
            Remove-Item -LiteralPath $TemporarySkillFile -Force
        }
    }
}

function Install-SkillCopy {
    param(
        [Parameter(Mandatory = $true)][string]$DestinationRoot,
        [Parameter(Mandatory = $true)]
        [ValidateSet("codex", "claude")]
        [string]$Platform
    )

    $Destination = Join-Path $DestinationRoot "ai-call-flow-navigator"
    $Stage = Join-Path $DestinationRoot (".ai-call-flow-navigator.install." + [guid]::NewGuid().ToString("N"))
    $Backup = Join-Path $DestinationRoot (".ai-call-flow-navigator.backup." + [guid]::NewGuid().ToString("N"))
    $PreviousMoved = $false

    New-Item -ItemType Directory -Force -Path $DestinationRoot | Out-Null
    try {
        Copy-Item -Path $Source -Destination $Stage -Recurse
        if ($Platform -eq "claude") {
            Set-ClaudeExplicitInvocationMetadata $Stage
        }
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
        Install-SkillCopy (Join-Path $HOME ".agents/skills") "codex"
        Install-SkillCopy (Join-Path $HOME ".claude/skills") "claude"
    }
    "codex" {
        Install-SkillCopy (Join-Path $HOME ".agents/skills") "codex"
    }
    "claude" {
        Install-SkillCopy (Join-Path $HOME ".claude/skills") "claude"
    }
}
