# TASK 2. Room (KSP-generated, not reflection-based) and Compose both ship
# their own consumer-rules.pro bundled in their AARs, applied automatically
# -- that's why this file stays short. Rules below cover this app's own
# code and libraries that don't self-manage.

# Keep data/domain model classes: Parcelable (kotlin-parcelize) and Room
# @Entity fields are read by generated code that R8 can't always trace
# through safely at high shrink levels.
-keep class com.example.intervaltimer.data.** { *; }
-keep class com.example.intervaltimer.domain.WorkoutProgress { *; }
-keep class com.example.intervaltimer.domain.ExecutionState { *; }

# Room's generated DB/DAO implementations.
-keep class * extends androidx.room.RoomDatabase

# Coroutines: suppress known-safe warnings from internal reflection use.
-dontwarn kotlinx.coroutines.**

# Play Services Location: keep model classes Room/GSON-style reflection
# might touch (defensive; Location itself is a platform class already kept).
-keep class com.google.android.gms.location.** { *; }
