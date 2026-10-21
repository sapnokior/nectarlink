$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME = 'C:\Users\adars\AppData\Local\Android\Sdk'
$env:ANDROID_NDK_HOME = 'C:\Users\adars\AppData\Local\Android\Sdk\ndk\29.0.14206865'
$env:LIB = 'C:\Program Files\Microsoft Visual Studio\18\Community\VC\Tools\MSVC\14.51.36231\lib\onecore\x64;C:\Program Files (x86)\Windows Kits\10\Lib\10.0.26100.0\ucrt\x64;C:\Program Files (x86)\Windows Kits\10\Lib\10.0.26100.0\um\x64'
$env:PATH = 'C:\Users\adars\.cargo\bin;C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin;' + $env:PATH
Set-Location 'C:\Users\adars\.gemini\antigravity\scratch\nectarlink\android'
.\gradlew.bat assembleDebug --stacktrace
