# 훈노트 (Hoon Note)

태블릿 **가로 화면**에 최적화된 안드로이드 강의 판서 + 화면 녹화 앱입니다.
아이캔노트(iCanNote)처럼 **넓은 노트 위에 PDF를 얹고, 오른쪽 여백에 판서**하는 구조로 2.0에서 화면을 개편했습니다.
판서 → 페이지 넘김 → PNG/PDF 저장 → MP4 화면녹화까지 한 화면에서 처리합니다.

- Kotlin / Android Views (Compose 미사용)
- minSdk 26 (Android 8.0) · targetSdk 34 · compileSdk 34
- AGP 8.5.2 · Kotlin 1.9.24 · Gradle 8.9

## 빌드 (GitHub Actions)

`.github/workflows/build-apk.yml` 이 푸시할 때마다 디버그 APK를 만듭니다.
`gradle-wrapper.jar` 없이 `gradle/actions/setup-gradle@v4` 로 Gradle 8.9 를 받아 `gradle assembleDebug` 를 실행합니다.

> **참고:** 이 워크플로 파일은 GitHub 앱(Pickle)의 `workflows` 권한이 없어 저장소에 아직 올라가 있지 않습니다.
> GitHub Settings → Applications → Pickle 에서 **Workflows 권한**을 켠 뒤 푸시하거나, GitHub 웹에서
> `Actions → New workflow` 로 같은 내용을 붙여넣으면 됩니다. 소스 자체는 로컬에서 빌드 검증을 마쳤습니다.

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

## 2.0 화면 구조 (아이캔노트형 3영역)

```
┌────────────────────────────────────────────────────────────┐
│ 상단 메뉴 바  강의 · 저장 · 페이지 · 화면/배경 · 녹화 · 설정  │
├──────┬─────────────────────────────────────┬───────────────┤
│ 썸네일│        노트 캔버스 (넓은 흰 노트)     │  세로 판서 툴바│
│  1   │  ┌──────────┐                       │  ● 녹화        │
│  2   │  │ PDF 페이지│   ← 풀이 여백 →      │  펜 형광       │
│  3   │  └──────────┘                       │  직선 화살     │
│  …   │                                     │  사각 원       │
│2/13  │                                     │  메모 지우개   │
│ + 🗑 │                                     │  이동 취소/재실│
│      │                                     │  굵기·색 팔레트│
│      │                                     │  ◀ 2 ▶        │
└──────┴─────────────────────────────────────┴───────────────┘
```

- **노트 캔버스 위에 PDF/이미지**: PDF를 화면 전체에 늘리지 않고, 노트(기본 2560×1440 와이드) 높이의 96%에
  맞춰 좌측 정렬로 얹습니다. 오른쪽(또는 양옆)에는 수학 풀이용 흰 여백이 남고, 옅은 점선 안내선이 그려집니다.
- **판서는 노트 전체에**: PDF 위든 오른쪽 여백이든 자유롭게 그려지고, PNG/PDF 내보내기에도 여백·PDF·판서가
  통째로 저장됩니다.
- **좌측 썸네일 바**: 페이지 번호 + 실시간/캐시 썸네일, 하단 `n / N Page` · `+`(추가) · `🗑`(삭제).
  상단 바의 레이어 아이콘으로 접고 펼칩니다.
- **우측 세로 툴바**: 오른손 S펜 조작용 2열 아이콘. 맨 위 `● 녹화`(녹화 중 경과 시간/중지), 도구 접기,
  펜·형광펜·직선·화살표·사각형·원·포스트잇·지우개·이동/줌·Undo·Redo, 굵기 3단 빠른 선택,
  8색 원탭 팔레트, 맨 아래 `◀ n ▶` 페이지 이동.
- **바 숨김**: 상단 바의 아이콘으로 좌측/우측 바를 각각 접거나 모두 숨길 수 있고, 숨겼을 때는
  좌상단의 작은 `도구` 버튼으로 복귀합니다. 전체화면(Immersive) 토글도 유지됩니다.

## 기능

### 화면 / 노트
- 가로 화면 고정 (`sensorLandscape`)
- 전체화면(Immersive) 토글 — 상태바/내비바 숨김, 스와이프로 일시 표시
- 노트 크기 선택(설정): 와이드 16:9 2560×1440 · FHD 1920×1080 · 4:3 2048×1536 · A4 가로 2970×2100
- PDF/이미지 정렬: **좌측 정렬(오른쪽 풀이 여백)** / 가운데 정렬, 노트 여백 0~12% 조절
- 배경: 화이트 / 크림 / 칠판 녹색 / 다크 네이비 / 모눈

### 강의 · 페이지
- 새 강의, PDF 열기(`PdfRenderer`), 이미지 열기(PNG/JPG, 최대 2400px)
- 페이지 추가(현재 페이지 뒤), 페이지 삭제, 이전/다음 페이지, 썸네일 탭으로 바로 이동
- 두 손가락 핀치 줌 + 팬 (0.5x~10x), `화면 맞춤`

### 판서 도구
- 펜(8색 원탭 팔레트), 형광펜(반투명), 지우개(획 단위)
- 도형: 직선, 화살표, 사각형, 원(타원) — 드래그로 크기 조절
- 이동/줌 도구: 한 손가락으로 화면 이동(손가락 허용 모드에서도 안전)
- 실행취소/다시실행 (페이지별 60단계)
- 포스트잇: 화면에 띄워 끌어서 이동, 메모 입력, × 로 삭제
- 팜리젝션: 스타일러스 우선 인식. 설정에서 **펜 전용 / 손가락 허용** 전환
  (기본값은 폰 테스트용 **손가락 허용**. 태블릿 수업에서는 **펜 전용** 권장)

### 저장
- 현재 페이지 **PNG** → `Pictures/HunsuNote` (노트 여백 + PDF + 판서 전체)
- 전체 페이지 **PDF** → `Download/HunsuNote` (`PdfDocument`, 노트 비율 유지)
- Android 10+ 는 MediaStore(스코프 저장소) 사용, Android 9 이하는 공용 폴더 + 미디어 스캔

### 화면 녹화 (MP4)
- `MediaProjection` + `MediaRecorder` + 포그라운드 서비스(`mediaProjection` 타입)
- 마이크 음성 함께 녹음(설정에서 ON/OFF)
- 설정: 해상도 720p/1080p, 프레임 24/30fps, 비트레이트 4/6/8/12Mbps
- Android 14 규칙 준수: **포그라운드 서비스 시작 → getMediaProjection → 가상 디스플레이** 순서
- 저장: `Movies/HunsuNote`, 알림의 **중지** 버튼으로 종료

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
- 노트 크기 변경은 **새 강의·새 페이지**부터 적용됩니다(이미 그린 판서 좌표가 어긋나지 않게 하기 위함).
  정렬/여백은 열려 있는 강의에도 바로 적용됩니다.
- 썸네일은 200px 폭으로 다시 그린 캐시입니다(판서 후 약 0.5초 뒤 갱신).
- 녹화는 화면 전체를 캡처합니다(앱 화면만 캡처하는 기능 없음).

## 구조

```
app/src/main/java/com/hunsu/note/
  HunsuApp.kt            Application, SharedPreferences 진입점
  Prefs.kt               설정 저장소(펜·녹화·노트 크기/정렬/여백)
  MainActivity.kt        상단 메뉴 바 · 썸네일 바 · 캔버스 · 세로 툴바 조립
  SettingsActivity.kt    설정 화면
  model/PageModel.kt     Lecture / Page / NoteLayout / NoteGeometry / DrawElement / StickyNote
  view/InkCanvasView.kt  터치·제스처·판서 캔버스(이동/줌 도구 포함)
  view/PagePainter.kt    노트 배경 + PDF 영역 + 여백 안내선 + 판서 + 포스트잇 그리기(화면/내보내기/썸네일 공용)
  io/PdfPageProvider.kt  PdfRenderer 래퍼(비율·렌더링 캐시)
  io/PageExporter.kt     PNG / PDF 저장, 썸네일 렌더링
  rec/ScreenRecordService.kt  MediaProjection + MediaRecorder 녹화 서비스
```
