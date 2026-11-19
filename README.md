<p align="center">
  <img src="docs/banner.png" alt="ROM 업데이터" width="100%">
</p>

<h3 align="center">원플러스 6의 VoltageOS를 폰에서 바로 업데이트하는 앱</h3>

<p align="center">
  새 빌드가 XDA에 올라오면 알림이 옵니다. 앱에서 받아 설치하고 재부팅하면 끝입니다. PC는 필요 없습니다.
</p>

<p align="center">
  <a href="https://github.com/HPotty36/rom-updater/releases/latest"><b>APK 받기</b></a> ·
  <a href="#시작하기">시작하기</a> ·
  <a href="#업데이트-한-번의-흐름">업데이트 한 번의 흐름</a> ·
  <a href="#동작-방식">동작 방식</a> ·
  <a href="#개발">개발</a>
</p>

<p align="center">
  <img alt="OnePlus 6" src="https://img.shields.io/badge/기기-OnePlus%206%20(enchilada)-2b6ff3">
  <img alt="Magisk 루트 필요" src="https://img.shields.io/badge/루트-Magisk-17191c">
  <img alt="서버 없음" src="https://img.shields.io/badge/서버-없음-6c7480">
</p>

<!-- screenshots -->

## 시작하기

이 앱은 [eightscrow의 VoltageOS UNOFFICIAL 빌드](https://xdaforums.com/t/4801565/)를 쓰는 원플러스 6 한 기종만을 위한 것입니다. 다른 기기나 다른 롬에서는 쓰지 마세요.

1. 폰을 **Magisk로 루팅**합니다. 롬을 설치하려면 루트 권한이 필요합니다.
2. [릴리스](https://github.com/HPotty36/rom-updater/releases/latest)에서 APK를 받아 설치합니다.
3. Magisk 앱의 **슈퍼유저** 탭에서 + 를 누르고 **ROM 업데이터**를 허용합니다.
4. 앱을 한 번 열고 알림을 허용합니다. 그다음부터는 6시간마다 알아서 확인합니다.

Magisk 앱은 지우지 마세요. 업데이트한 뒤에도 루트를 지키는 단계가 그 앱 안의 도구를 씁니다.

## 업데이트 한 번의 흐름

1. **알림이 옵니다.** XDA 스레드에 지금 쓰는 것보다 새로운 빌드가 올라왔을 때 한 번만 옵니다.
2. **다운로드를 누릅니다.** 1.3GB쯤 되고, 다른 앱을 써도 계속 받습니다. 끊기면 이어서 받습니다.
3. **설치하기를 누릅니다.** 지금 쓰는 쪽은 그대로 두고 반대쪽 슬롯에 설치합니다. 6분쯤 걸리고, 그동안 폰을 써도 됩니다.
4. **재부팅을 누릅니다.** 새 빌드로 켜지고, 데이터와 루트는 그대로입니다.

새 빌드가 부팅에 실패하면 폰이 알아서 이전 슬롯으로 돌아갑니다.

## 동작 방식

| 단계 | 방법 |
| --- | --- |
| 새 빌드 확인 | XDA 스레드를 화면 밖 WebView로 열어 제목의 빌드 날짜를 읽고, 폰의 빌드 날짜와 비교합니다. |
| 다운로드 | 스레드 첫 글의 Filen 공유 폴더에서 ROM ZIP을 받아 복호화합니다. 같은 폴더의 `CHECKSUMS.txt`로 SHA-256을 확인합니다. |
| 설치 | 루트 권한으로 ZIP을 `/data/ota_package`에 두고 `update_engine`에 넘깁니다. 공식 OTA와 같은 경로입니다. |
| 루트 지키기 | 설치 직후, 방금 쓴 반대쪽 슬롯의 boot를 Magisk 앱의 도구로 패치합니다. |

서버가 없습니다. 확인도, 다운로드도, 설치도 모두 폰에서 합니다.

### 다운로드가 안 될 때

Filen 링크가 바뀌거나 직접 받기가 막히면, 브라우저로 ROM ZIP을 받은 뒤 앱 아래쪽의 **받아 둔 ZIP 파일로 설치**를 누르면 됩니다.

## 개발

Kotlin과 Jetpack Compose로 만들었고, 바깥 라이브러리는 WorkManager 하나입니다.

```
app/src/main/java/dev/romupdater/
  Core.kt           스레드 제목과 체크섬 읽기, ROM ZIP 안의 payload 위치 찾기
  Filen.kt          Filen 공유 폴더 목록, 다운로드, 복호화
  Repo.kt           앱 상태, 스레드 확인, 알림
  Workers.kt        6시간마다 확인, 백그라운드 다운로드
  Installer.kt      루트로 하는 설치 절차와 루트 지키기
  EngineRunner.kt   루트로 따로 실행되어 update_engine을 부르는 작은 프로그램
  MainActivity.kt   화면
stubs/              숨김 API android.os.UpdateEngine의 컴파일용 껍데기
tools/              루팅할 때 쓴 스크립트, README 배너 만들기
docs/               README에 쓰는 이미지
```

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

테스트는 스레드 제목에서 빌드 날짜 읽기, 날짜 비교, Filen 링크와 체크섬 읽기, ROM ZIP 안에서 payload 위치 찾기를 확인합니다.

새 빌드가 없을 때 업데이트 흐름을 보려면, 디버그 빌드를 더 오래된 빌드인 척 실행합니다.

```bash
adb shell am start -S -n dev.romupdater/.MainActivity --es installedDate 20261001
```

## 디자인

옅은 회색 바탕에 흰 카드, 그리고 파랑 하나만 씁니다. 파랑은 지금 누를 버튼과 진행 막대에만 둡니다.

- 화면 맨 위의 큰 문장 하나가 지금 상태를 말합니다. "최신 빌드를 쓰고 있어요", "새 빌드가 나왔어요", "설치할 준비가 됐어요".
- 주 버튼은 항상 화면 맨 아래 같은 자리에 하나만 있습니다. 상태에 따라 글자만 바뀝니다.
- 받는 중과 설치하는 중에는 숫자를 가장 크게 보여 줍니다.
- 설치는 되돌리기 번거로운 일이라, 누르면 아래에서 올라오는 시트로 한 번 더 묻습니다.
- 다크 모드는 기기 설정을 따릅니다.

## 알려진 한계

- 원플러스 6과 이 스레드의 빌드에서만 확인했습니다. 실제로 새 버전으로 올리는 업데이트는 아직 해 보지 못했고, 같은 빌드를 다시 설치하는 것으로 시험했습니다.
- 새 빌드 확인은 스레드 제목의 `[enchilada][일/월/연도]` 형식에, 다운로드는 첫 글의 Filen 링크에 기대고 있습니다. 메인테이너가 형식이나 올리는 곳을 바꾸면 그 단계가 멈춥니다.
- 폴더에 ROM ZIP이 여러 개면 이름이 가장 짧은 것을 받습니다.
- 릴리스의 APK는 디버그 키로 서명한 빌드입니다.
- 루팅과 롬 설치는 기기를 못 쓰게 만들 수 있습니다. 직접 책임질 수 있을 때만 쓰세요.
