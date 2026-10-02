# Strip Log.d / Log.v from release.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# No -keep rules for the reader libraries.
#
# There used to be a block of them here, justified as "the modules are published
# as AARs". That reasoning was wrong: R8 runs on the *app*, so nothing in this
# file affects what a consumer of the published AARs sees. Rules a library needs
# its consumers to apply belong in that library's own consumerProguardFiles,
# which ship inside the AAR — none of these modules need any, because none of
# them use reflection, XML view inflation, serialization or Parcelable.
#
# The rules also cost something: keeping the whole public surface of :card,
# :passport and :qr stopped R8 shrinking a sample app that only exercises part of it.
# Everything the sample actually uses it calls directly, so reachability keeps
# it; the activities are kept by the manifest.
#
# If you add reflection, JSON binding or XML-inflated views later, add the keep
# to the module that needs it (consumerProguardFiles), not here.
