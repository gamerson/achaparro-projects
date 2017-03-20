package com.liferay.upgrade.properties.locator;

import com.liferay.portal.kernel.util.ArrayUtil;
import jdk.internal.org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by achaparro on 18/02/17.
 */
public class ConfigurationClassData {

    public ConfigurationClassData(InputStream is) throws IOException {
        ClassReader cr = new ClassReader(is);
        cr.accept(new ConfigClassVisitor(), ClassReader.SKIP_CODE);
    }

    public static String getSuperClass() {
        return _superClass;
    }

    public static String[] getConfigFields() {
        return _configFields;
    }

    private static void addConfigField(String configField) {
        _configFields = ArrayUtil.append(_configFields, configField);
    }

    private static void setSuperClass(String superClass) {
        _superClass = superClass;
    }

    private static String _superClass;
    private static String[] _configFields = new String[0];

    private class MethodAnnotationScanner extends MethodVisitor {

        public MethodAnnotationScanner(String fieldName) {
            super(Opcodes.ASM5);

            _fieldName = fieldName;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (desc.equals("LaQute/bnd/annotation/metatype/Meta$AD;")) {
                addConfigField(_fieldName);
            }

            return null;
        }

        private String _fieldName;
    }

    private class ConfigClassVisitor extends ClassVisitor {

        public ConfigClassVisitor() {
            super(Opcodes.ASM5);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions){
            return new MethodAnnotationScanner(name);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            setSuperClass(superName);

            super.visit(version, access, name, signature, superName, interfaces);
        }
    }
}

