package com.github.xingjianluo.versionedobjectstoragejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * @author Vincent
 * @version 1.0
 */
@SpringBootTest(properties = "storage.data-dir=${java.io.tmpdir}/vos-application-tests")
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
