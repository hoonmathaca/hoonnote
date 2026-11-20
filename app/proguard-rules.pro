# 훈노트 ProGuard 규칙
# 현재 릴리스 빌드는 minifyEnabled false 이지만, 추후 축소 빌드를 켤 때를 위한 기본 규칙입니다.

# MediaProjection / MediaRecorder 는 프레임워크 API 라 별도 규칙이 필요 없습니다.
# 뷰 바인딩이 생성하는 클래스는 이름이 유지되어야 합니다.
-keepnames class com.hunsu.note.databinding.** { *; }

# Kotlin 메타데이터 유지
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
