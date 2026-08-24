[CmdletBinding()]
param()

$ErrorActionPreference='Stop'
$qa=Split-Path $PSScriptRoot -Parent
$root=Split-Path $qa -Parent
$db=Join-Path $qa 'plugins\BlockStock\blockeco.db'
$config=Join-Path $qa 'plugins\BlockStock\config.yml'
$escrowData=Join-Path $qa 'plugins\Essentials\userdata\00000000-0000-0000-0000-000000000001.yml'
$jar=Join-Path $root 'build\libs\blockstock-0.1.0-SNAPSHOT-all.jar'
$java='E:\java\jdk21\bin\java.exe'
function Wait-Port([int]$port){for($i=0;$i -lt 80;$i++){if(Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue){return};Start-Sleep -Milliseconds 500};throw "QA port $port did not open"}
function Set-MarketZone([string]$zone){$c=[IO.File]::ReadAllText($config,[Text.UTF8Encoding]::new($false));$c=[regex]::Replace($c,'(?m)^  time-zone: .*$',"  time-zone: $zone");[IO.File]::WriteAllText($config,$c,[Text.UTF8Encoding]::new($false))}
function Start-QA([string]$zone){Set-MarketZone $zone;$script:qaProcess=Start-Process -FilePath $java -ArgumentList @('-Xms512M','-Xmx1G','-jar','paper-1.21.4.jar','--nogui') -WorkingDirectory $qa -WindowStyle Hidden -PassThru;Wait-Port 25566;Wait-Port 25567;Start-Sleep -Seconds 8;$log=(Get-Content (Join-Path $qa 'logs\latest.log') -Tail 240) -join "`n";if($log -match 'BlockStock 启动失败|startup failure'){throw 'BlockStock QA enable failed; inspect qa-server/logs/latest.log'}}
function Stop-QA{if($null -eq $script:qaProcess -or $script:qaProcess.HasExited){return};Push-Location $PSScriptRoot;try{& node --input-type=module -e "import net from 'node:net';import{readFileSync}from'node:fs';const p=/^rcon.password=(.*)$/m.exec(readFileSync('../server.properties','utf8'))[1],w=(i,t,b)=>{const q=Buffer.from(b),x=Buffer.alloc(14+q.length);x.writeInt32LE(10+q.length);x.writeInt32LE(i,4);x.writeInt32LE(t,8);q.copy(x,12);return x};const s=net.createConnection({host:'127.0.0.1',port:25567});let a=0;s.on('connect',()=>s.write(w(1,3,p)));s.on('data',()=>{if(a++)s.end();else s.write(w(2,2,'stop'))});"}finally{Pop-Location};$script:qaProcess.WaitForExit(30000)|Out-Null;if(!$script:qaProcess.HasExited){$script:qaProcess.Kill();$script:qaProcess.WaitForExit()}}
function Run-Gui([string]$phase){$env:QA_CLOCK_PHASE=$phase;Push-Location $PSScriptRoot;try{& node .\bluechip-gui-e2e.mjs;if($LASTEXITCODE -ne 0){throw "GUI acceptance failed in $phase"}}finally{Pop-Location;Remove-Item Env:QA_CLOCK_PHASE -ErrorAction SilentlyContinue}}
function Set-DividendFixture{Push-Location $PSScriptRoot;try{& node --input-type=module -e "import {DatabaseSync} from 'node:sqlite';const d=new DatabaseSync('../plugins/BlockStock/blockeco.db');d.exec(\"UPDATE bluechip_companies SET next_dividend_at='2000-01-01T00:00:00Z'\");d.close();"}finally{Pop-Location}}
function Reset-EscrowFixture{if(!(Test-Path $escrowData)){return};Copy-Item -Force $escrowData ($escrowData+'.qa-backup-'+(Get-Date -Format 'yyyyMMddHHmmss'));$c=[IO.File]::ReadAllText($escrowData,[Text.UTF8Encoding]::new($false));$c=[regex]::Replace($c,'(?m)^money: .*$',"money: '0.0'");[IO.File]::WriteAllText($escrowData,$c,[Text.UTF8Encoding]::new($false))}
if(Get-NetTCPConnection -LocalPort 25566 -ErrorAction SilentlyContinue){throw 'QA port 25566 already in use; refusing unknown process'}
Copy-Item -Force $jar (Join-Path $qa 'plugins\BlockStock.jar')
Copy-Item -Force (Join-Path $root 'src\main\resources\config.yml') $config
Reset-EscrowFixture
if(Test-Path $db){Move-Item -LiteralPath $db -Destination (Join-Path (Split-Path $db -Parent) ('blockeco.qa-backup-'+(Get-Date -Format 'yyyyMMddHHmmss')+'.db'))}
try{
  Start-QA 'Etc/GMT+10';Run-Gui 'PREOPEN';Stop-QA
  Start-QA 'Etc/UTC';Run-Gui 'OPEN';Stop-QA
  Start-QA 'Etc/GMT-12';Run-Gui 'POSTCLOSE';Start-Sleep -Seconds 70;Stop-QA
  Set-DividendFixture
  Start-QA 'Etc/GMT-12';Start-Sleep -Seconds 70;Stop-QA
  Start-QA 'Etc/GMT-12';Start-Sleep -Seconds 70;Stop-QA
  Push-Location $PSScriptRoot;try{& node .\bluechip-gui-e2e.mjs --ledger;if($LASTEXITCODE -ne 0){throw 'stopped-server ledger acceptance failed'}}finally{Pop-Location}
  Write-Output 'BLUECHIP_QA_RUNNER_PASS'
}finally{Stop-QA}
