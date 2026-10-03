# Normein kernel publication plan

M0 pins an unmodified source checkout of `com.xqiou:normein-dsl:0.3.0` at
`0a3ae1de844c92635fbbc03406a13cb0e8920c03`. The composite build substitutes this exact module;
it does not consume invoice applications or publish Mantra's `apps/` projects.

The pinned Normein build already defines a Maven publication with binary, sources and Javadoc
JARs, Apache-2.0 POM metadata, local staging, Sonatype upload and optional in-memory signing.
Mantra will use Maven Central for the first coordinated library release, following the existing
Normein publication mechanism. The release is scheduled after M1; M0 publishes source only.

For the coordinated release:

1. Normein produces and verifies `com.xqiou:normein-dsl:<version>` from the reviewed release commit.
   Run its staging/POM and isolated-consumer checks in the Normein repository. Any change to that
   build or its kernel remains a Normein task, tracked by an RFC and Mantra contract tests.
2. Verify the publisher owns the Maven namespace and configure Central credentials and signing
   secrets in the release workflow. Never store signing keys or credentials in either repository.
3. Mantra updates the source lock and contract expectations, verifies the released POM/transitives,
   then consumes that exact Maven version in a clean consumer build without composite substitution.
4. Publish the Mantra library modules with binary/sources/API documentation and dependency notices
   only after that consumer build passes. CLI/workbench distributions carry the applicable notices;
   domain applications remain source demonstrations, later loaded as scheme packages in M4.

Until the artifact is available publicly, building Mantra requires access to the private Normein
repository or an authorized local checkout. Mantra Actions and Dependabot use a dedicated read-only
deploy key for that repository (`NORMEIN_DEPLOY_KEY`); checkout removes credentials before building.
The public Mantra repository does not make the private kernel checkout accessible to external forks.
Maintainers run full JVM verification on trusted branches; PR workflows do not use
`pull_request_target` to run untrusted code with private-repository credentials.

This records the M0 distribution choice and release sequence. It does not claim that a Maven artifact
has already been uploaded, or that release namespace/signing credentials are already configured.
