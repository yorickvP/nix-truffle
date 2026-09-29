package nixtruffle;

import com.oracle.truffle.api.TruffleFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class NixFileDetector implements TruffleFile.FileTypeDetector {
    @Override
    public String findMimeType(TruffleFile file) {
        String name = file.getName();
        return name != null && name.endsWith(".nix") ? NixLanguage.MIME : null;
    }

    @Override
    public Charset findEncoding(TruffleFile file) {
        return StandardCharsets.UTF_8;
    }
}
