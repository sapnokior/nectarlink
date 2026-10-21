$env:LIB = 'C:\Program Files\Microsoft Visual Studio\18\Community\VC\Tools\MSVC\14.51.36231\lib\onecore\x64;C:\Program Files (x86)\Windows Kits\10\Lib\10.0.26100.0\ucrt\x64;C:\Program Files (x86)\Windows Kits\10\Lib\10.0.26100.0\um\x64'
$env:PATH = 'C:\Users\adars\.cargo\bin;' + $env:PATH
cargo install uniffi_bindgen --version 0.32.2
