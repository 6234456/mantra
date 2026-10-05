import com.xqiou.mantra.core.api.RuntimeVersions;

/** Reads the public API from the selected built JAR, without a source composite. */
public final class RuntimeVersionProbe {
    public static void main(String[] arguments) {
        if (arguments.length != 2) throw new IllegalArgumentException("Expected Mantra and Normein versions");
        String actualMantra = RuntimeVersions.INSTANCE.getMantra();
        String actualNormein = RuntimeVersions.INSTANCE.getNormein();
        if (!arguments[0].equals(actualMantra) || !arguments[1].equals(actualNormein)) {
            throw new AssertionError("Selected " + arguments[0] + "/" + arguments[1]
                + ", built artifact reports " + actualMantra + "/" + actualNormein);
        }
        System.out.println("MANTRA_RUNTIME_IDENTITY_OK " + actualMantra + " " + actualNormein);
    }
}
