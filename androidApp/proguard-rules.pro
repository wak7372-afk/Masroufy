# Preserve names to keep audit evidence and device crash reports readable.
-dontobfuscate
# The separately compiled Release instrumentation runner shares the app's Kotlin
# runtime. Keep its public API (including Intrinsics), which the runner calls.
-keep class kotlin.** { public protected *; }
-keep class androidx.tracing.** { public protected *; }
-keep class kotlinx.serialization.** { public protected *; }
# PDFBox resolves parts of its document/font/crypto model dynamically. Preserve
# its API, including the encrypted PDF fixtures exercised by Release tests.
-keep class com.tom_roush.pdfbox.** { *; }
# Native JNI callbacks and entry points use names rather than Java references.
-keep class com.googlecode.tesseract.android.** { *; }
# These are also exercised against the real, optimized Release APK by the
# instrumentation suite. Preserve their callable entry points, not test data.
-keep class om.masroofi.app.EncryptedStore { *; }
-keep class om.masroofi.app.OfflineStatementReader { *; }
-keep class om.masroofi.app.StatementPdfReader { *; }
-keep class om.masroofi.app.StatementImaging { *; }
-keep class om.masroofi.core.** { *; }
# Bouncy Castle discovers algorithms by name. Retain provider implementations;
# its unused EST/TLS support must be removed from the distributed application.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }
-checkdiscard class org.bouncycastle.est.**
# Optional PDFBox JPEG2000 codec is deliberately not shipped. PDFBox checks its
# availability and reports unsupported content; retain normal PNG/JPEG support.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
# JNDI is not part of Android. Offline PDF password processing does not use the
# provider's desktop LDAP certificate-store helpers. Do not ignore other classes.
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult
