// Compile-only stand-ins for the hidden android.os.UpdateEngine API.
// The real classes come from the framework at runtime.
plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
