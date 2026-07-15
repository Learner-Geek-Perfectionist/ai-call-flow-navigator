$ErrorActionPreference = "Stop"
$Installer = Join-Path $PSScriptRoot "install-skill.py"
$InstallerArguments = $args

function Test-CompatiblePython {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [string[]]$Prefix = @()
    )

    if (-not (Get-Command $Command -CommandType Application -ErrorAction SilentlyContinue)) {
        return $false
    }

    try {
        & $Command @Prefix -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 8) else 1)" *> $null
        return $LASTEXITCODE -eq 0
    }
    catch {
        return $false
    }
}

$Candidates = @(
    @{ Command = "py"; Prefix = @("-3") },
    @{ Command = "python3"; Prefix = @() },
    @{ Command = "python"; Prefix = @() }
)

foreach ($Candidate in $Candidates) {
    $Command = $Candidate.Command
    $Prefix = [string[]]$Candidate.Prefix
    if (Test-CompatiblePython $Command $Prefix) {
        $PythonArguments = @($Prefix) + @($Installer) + @($InstallerArguments)
        & $Command @PythonArguments
        exit $LASTEXITCODE
    }
}

[Console]::Error.WriteLine("Python 3.8 or newer is required to install the Skill.")
exit 1
