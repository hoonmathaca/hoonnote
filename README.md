# 훈수노트 (Hunsu Note)

태블릿 **가로 화면**에 최적화된 안드로이드 강의 판서 + 화면 녹화 앱의 1차 프로토타입입니다.
아이캔노트의 경량 대체용으로, 판서 → 페이지 넘김 → PNG/PDF 저장 → MP4 화면녹화까지 한 화면에서 처리합니다.

- Kotlin / Android Views (Compose 미사용)
- minSdk 26 (Android 8.0) · targetSdk 34 · compileSdk 34
- AGP 8.5.2 · Kotlin 1.9.24 · Gradle 8.9

## 빌드 (GitHub Actions)

`.github/workflows/build-apk.yml` 이 푸시할 때마다 디버그 APK를 만듭니다.
`gradle-wrapper.jar` 없이 `gradle/actions/setup-gradle@v4` 로 Gradle 8.9 를 받아 `gradle assembleDebug` 를 실행합니다.

1. GitHub 에 `hoonmathaca/hoonnote` 저장소를 만든다.
2. 이 폴더를 그대로 푸시한다.
3. Actions 탭 → `Build Debug APK` → 실행 결과의 **Artifacts** 에서 `app-debug-apk` 다운로드.
4. APK 를 태블릿에 설치(출처를 알 수 없는 앱 설치 허용 필요).

로컬 빌드도 동일합니다:

```bash
# JDK 17 + Android SDK(platform 34, build-tools 34.0.0) 필요
gradle assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk
```

## 기능

### 화면
- 가로 화면 고정 (`sensorLandscape`)
- 전체화면(Immersive) 토글 — 상태바/내비바 숨김, 스와이프로 일시 표시
- 도구 막대 **숨김/펼치기**: 강의 중 화면을 가리지 않도록 상단 바를 접고, 접힌 상태에서도 `도구 / ◀ n/N ▶ / ● 녹화` 미니 바는 남습니다.
- 녹화를 시작하면 도구 막대가 자동으로 접힙니다(설정에서 끌 수 있음).

### 강의 · 페이지
- 새 강의: 배경 선택 **화이트 / 칠판 녹색 / 다크 네이비 / 모눈**
- PDF 열기: `PdfRenderer` 로 페이지별 렌더링, 페이지 수만큼 자동 생성
- 이미지 열기: PNG/JPG (ImageDecoder 로 최대 2400px 로 축소)
- 페이지 추가(현재 페이지 뒤에 빈 페이지), 이전/다음 페이지
- 두 손가락 핀치 줌 + 팬 (0.5x~10x), 페이지에 맞춤

### 판서 도구
- 펜 (8색 프리셋, 굵기 슬라이더), 형광펜(반투명), 지우개
- 도형: 직선, 화살표, 사각형, 원(타원) — 드래그로 크기 조절
- 실행취소/다시실행 (페이지별 60단계)
- 포스트잇: 화면에 띄워 끌어서 이동, 메모 입력, × 로 삭제 (페이지별 저장)
- 팜리젝션: 스타일러스(`TOOL_TYPE_STYLUS`) 우선 인식. 설정에서 **펜 전용 / 손가락 허용** 전환.
  - 기본값은 **손가락 허용**입니다. 스타일러스 없이 폰에서 먼저 테스트할 수 있게 하려는 것이며,
    실제 태블릿 수업에서는 **펜 전용**으로 바꾸면 손바닥이 닿아도 그려지지 않습니다.

### 저장
- 현재 페이지 **PNG** → `Pictures/HunsuNote` (갤러리에서 바로 확인)
- 전체 페이지 **PDF** → `Download/HunsuNote` (`PdfDocument`, 페이지 비율 유지)
- Android 10+ 는 MediaStore(스코프 저장소) 사용, Android 9 이하는 공용 폴더 + 미디어 스캔

### 화면 녹화 (MP4)
- `MediaProjection` + `MediaRecorder` + 포그라운드 서비스(`mediaProjection` 타입)
- 마이크 음성 함께 녹음(설정에서 ON/OFF)
- 설정: 해상도 720p/1080p(화면 비율 유지), 프레임 24/30fps, 비트레이트 4/6/8/12Mbps
- Android 14 규칙 준수: **포그라운드 서비스 시작 → getMediaProjection → 가상 디스플레이** 순서
- 저장: `Movies/HunsuNote` (갤러리에서 바로 재생), 알림의 **중지** 버튼으로 종료

### 설정
- 녹화 설정 / 기본 펜 색·굵기 / 팜리젝션 모드 / 시작 시 전체화면 / 녹화 시 도구막대 자동 숨김
- 모든 설정 초기화

## 권한

| 권한 | 용도 |
| --- | --- |
| `RECORD_AUDIO` | 마이크 음성 녹음 |
| `POST_NOTIFICATIONS` | 녹화 중 알림(Android 13+) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 화면 녹화 서비스(Android 14+) |
| `WRITE_EXTERNAL_STORAGE` (maxSdk 28) | Android 9 이하에서 Pictures/Downloads 직접 저장 |

## 알려진 한계 (프로토타입)

- 판서 데이터는 앱을 끄면 사라집니다(파일 저장/불러오기 없음). 결과물은 PNG/PDF/MP4 로 남깁니다.
- 지우개는 **획 단위**로 지웁니다(부분 지우개 아님).
- PDF·이미지 페이지는 world 폭 1600px 기준으로 렌더링합니다(인쇄용 고해상도는 아님).
- 녹화는 화면 전체를 캡처합니다(앱 화면만 캡처하는 기능 없음).

## 구조

```
app/src/main/java/com/hunsu/note/
  HunsuApp.kt            Application, SharedPreferences 진입점
  Prefs.kt               설정 저장소
  MainActivity.kt        판서 화면 전체 조립
  SettingsActivity.kt    설정 화면
  model/PageModel.kt     Lecture / Page / DrawElement / StickyNote
  view/InkCanvasView.kt  터치·제스처·판서 캔버스
  view/PagePainter.kt    배경·판서·포스트잇 그리기(화면/내보내기 공용)
  io/PdfPageProvider.kt  PdfRenderer 래퍼(비율·렌더링 캐시)
  io/PageExporter.kt     PNG / PDF 저장
  rec/ScreenRecordService.kt  MediaProjection + MediaRecorder 녹화 서비스
```
