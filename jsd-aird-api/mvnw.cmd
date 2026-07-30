@echo off
setlocal

set "MAVEN_VERSION=3.9.9"
set "MAVEN_DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip"

if defined MAVEN_USER_HOME (
  set "MAVEN_CACHE=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%"
) else (
  set "MAVEN_CACHE=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%"
)

set "MAVEN_HOME=%MAVEN_CACHE%\apache-maven-%MAVEN_VERSION%"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  if not exist "%MAVEN_CACHE%" mkdir "%MAVEN_CACHE%"
  set "MAVEN_ARCHIVE=%MAVEN_CACHE%\apache-maven-%MAVEN_VERSION%-bin.zip"

  powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command ^
    "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri '%MAVEN_DIST_URL%' -OutFile '%MAVEN_ARCHIVE%'"
  if errorlevel 1 (
    echo ERROR: Failed to download Maven %MAVEN_VERSION%.
    exit /b 1
  )

  powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command ^
    "Expand-Archive -LiteralPath '%MAVEN_ARCHIVE%' -DestinationPath '%MAVEN_CACHE%' -Force"
  if errorlevel 1 (
    echo ERROR: Failed to extract Maven %MAVEN_VERSION%.
    exit /b 1
  )
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%

