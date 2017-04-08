package com.liferay.upgrade.properties.locator;

import com.liferay.portal.kernel.util.*;

import java.io.*;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Created by achaparro on 28/12/16.
 */
public class PropertiesLocator {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Please, specify the following arguments: ");
            System.out.println("URL to old portal-ext.properties");
            System.out.println("URL to a Liferay bundle");

            return;
        }

        String oldPropertiesFileURL = args[0];
        String bundleURL = args[1];

        _outputFile = generateOutputFile();

        String title = "Checking the location for old properties in the new version";

        _outputFile.println(title);
        printUnderline(title);

        try {
            Properties oldProperties = getProperties(oldPropertiesFileURL);

            Properties newProperties = getCurrentPortalProperties(bundleURL);

            SortedSet<String> remainedProperties = new TreeSet<String>();

            SortedSet<String> removedProperties = getRemovedProperties(oldProperties, newProperties, remainedProperties);

            removedProperties = manageExceptions(removedProperties);

            _outputFile.println();
            removedProperties = checkPortletProperties(removedProperties, bundleURL + "/osgi");

            _outputFile.println();
            removedProperties = checkConfigurationProperties(removedProperties, bundleURL + "/osgi");

            _outputFile.println();
            _outputFile.println("We haven't found a new property for the following old properties (check if you still need them or check the documentation to find a replacement):");
            printProperties(removedProperties);

            _outputFile.println();
            _outputFile.println("The following properties still exist in the new portal.properties:");
            printProperties(remainedProperties);

            System.out.println("Done!");
        }
        finally {
            _outputFile.close();
        }
    }

    protected static Properties getCurrentPortalProperties(String bundleURL) throws Exception {
        Properties properties = new Properties();

        try (Stream<Path> paths = Files.find(
                Paths.get(bundleURL), Integer.MAX_VALUE,
                (path,attrs) -> attrs.isRegularFile()
                        && path.toString().endsWith(_PORTAL_IMPL_RELATIVE_PATH))) {
            paths.limit(1).forEach(path -> {
                try {
                    getPropertiesFromJar("jar:file:" + path.toString() + "!/portal.properties", properties);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        if (properties.size() == 0) {
            throw new Exception("File portal.properties doesn't exist in " + bundleURL);
        }

        return properties;
    }

    protected static Properties getProperties(String propertiesURL) throws Exception {
        File propertiesFile = new File(propertiesURL);

        try {
            FileInputStream fileInput = new FileInputStream(propertiesFile);

            Properties properties = new Properties();
            properties.load(fileInput);
            fileInput.close();

            return properties;
        }
        catch (Exception e) {
            System.out.println("Unable to read properties file " + propertiesFile.getAbsolutePath());

            throw e;
        }
    }

    protected static void getPropertiesFromJar(String propertiesJarURL, Properties properties) throws Exception {
        try {
            URL url = new URL(propertiesJarURL);
            InputStream is = url.openStream();

            properties.load(is);
            is.close();
        }
        catch (Exception e) {
            System.out.println("Unable to read properties file " + propertiesJarURL);

            throw e;
        }
    }

    protected static SortedSet<String> getRemovedProperties(Properties oldProperties, Properties newProperties, SortedSet<String> remainedProperties) {
        SortedSet<String> removedProperties = new TreeSet<String>();

        Enumeration enuKeys = oldProperties.keys();

        while (enuKeys.hasMoreElements()) {
            String key = enuKeys.nextElement().toString();

            if (newProperties.getProperty(key) == null) {
                removedProperties.add(key);
            }
            else {
                remainedProperties.add(key);
            }
        }

        return removedProperties;
    }

    protected static PrintWriter generateOutputFile() throws FileNotFoundException {
        try {
            return new PrintWriter("checkProperties" + LocalDateTime.now() + ".out");
        }
        catch (FileNotFoundException e) {
            System.out.println("Unable to generate ouput file");

            throw e;
        }
    }

    protected static SortedSet<String> checkPortletProperties(SortedSet<String> properties, String rootPath) throws Exception {
        List<Pair<String, String[]>> portletsProperties = new ArrayList<>();

        Files.walk(Paths.get(rootPath))
            // We don't need to analyze war files since, they are still like in previous versions so properties still remain in the same place
            .filter(path -> ((path.toFile().getAbsolutePath().endsWith(".jar")) || (path.toFile().getAbsolutePath().endsWith(".lpkg"))) && (!path.toFile().getAbsolutePath().contains("/osgi/state/")))
            .forEach(path -> {
                try {
                    String absolutePath = path.toFile().getAbsolutePath();

                    Properties portletProperties = new Properties();

                    if (isLiferayJar(absolutePath)) {
                        JarFile jar = new JarFile(absolutePath);

                        JarEntry portletPropertiesFile = jar.getJarEntry("portlet.properties");

                        if (portletPropertiesFile != null) {
                            getPropertiesFromJar("jar:file:" + absolutePath + "!/portlet.properties", portletProperties);
                        }

                        Enumeration enuKeys = portletProperties.keys();

                        String[] propertyKeys = new String[0];

                        while (enuKeys.hasMoreElements()) {
                            propertyKeys = ArrayUtil.append(propertyKeys, (String) enuKeys.nextElement());
                        }

                        if (propertyKeys.length != 0) {
                            portletsProperties.add(new Pair<String, String[]>(absolutePath + "/portlet.properties", propertyKeys));
                        }
                    }
                    else if (absolutePath.endsWith(".lpkg")) {
                        ZipFile zipFile = new ZipFile(absolutePath);

                        Enumeration enu = zipFile.entries();

                        while(enu.hasMoreElements()) {
                            ZipEntry zipEntry = (ZipEntry) enu.nextElement();

                            if (isLiferayJar(zipEntry.getName())) {
                                try (JarInputStream jarIs = new JarInputStream(zipFile.getInputStream(zipEntry))) {
                                    ZipEntry zipEntryJar = jarIs.getNextEntry();

                                    while (zipEntryJar != null) {
                                        if (zipEntryJar.getName().equals("portlet.properties")) {
                                            portletProperties.load(jarIs);

                                            Enumeration enuKeys = portletProperties.keys();

                                            String[] propertyKeys = new String[0];

                                            while (enuKeys.hasMoreElements()) {
                                                propertyKeys = ArrayUtil.append(propertyKeys, (String) enuKeys.nextElement());
                                            }

                                            if (propertyKeys.length != 0) {
                                                portletsProperties.add(new Pair<String, String[]>(absolutePath + "/" + zipEntry.getName() + "/portlet.properties", propertyKeys));
                                            }

                                            break;
                                        }

                                        zipEntryJar = jarIs.getNextEntry();
                                    }
                                }
                                catch (Exception e) {
                                    continue;
                                }
                            }
                        }
                    }
                }
                catch (Exception e) {
                    System.out.println("Unable to get portlet properties");

                    e.printStackTrace();

                    return;
                }
            });

        SortedMap<String, SortedMap<String, String>> foundedProperties = new TreeMap<>();

        for (String property : properties) {
            SortedMap<String, String> mostLikelyMatches = getMostLikelyMatches(property, portletsProperties, getPortletNames(property));

            if (mostLikelyMatches.size() > 0) {
                foundedProperties.put(property, mostLikelyMatches);
            }
        }

        if (foundedProperties.size() > 0) {
            _outputFile.println("Some properties have been moved to a module portlet.properties: ");

            for (Map.Entry<String, SortedMap<String, String>> entry : foundedProperties.entrySet()) {
                String foundedProperty = entry.getKey();

                _outputFile.print("\t");
                _outputFile.println(foundedProperty + " can match with the following portlet properties:");

                Map<String, String> matches = entry.getValue();

                for (Map.Entry<String, String> match : matches.entrySet()) {
                    _outputFile.print("\t\t");
                    _outputFile.println(match.getKey() + " from " + match.getValue());
                }

                properties.remove(foundedProperty);
            }
        }

        return properties;
    }

    protected static SortedSet<String> checkConfigurationProperties(SortedSet<String> properties, String rootPath) throws IOException {
        Map<String, ConfigurationClassData> configClassesMap = new HashMap<>();

        Files.walk(Paths.get(rootPath))
            .filter(path -> ((path.toFile().getAbsolutePath().endsWith(".jar")) || (path.toFile().getAbsolutePath().endsWith(".lpkg"))) && (!path.toFile().getAbsolutePath().contains("/osgi/state/")))
            .forEach(path -> {
                try {
                    String absolutePath = path.toFile().getAbsolutePath();

                    if (isLiferayJar(absolutePath)) {
                        try (JarInputStream jarIs = new JarInputStream(new FileInputStream(absolutePath))) {
                            ZipEntry zipEntryJar = jarIs.getNextEntry();

                            while (zipEntryJar != null) {
                                if (zipEntryJar.getName().endsWith("Configuration.class")) {
                                    configClassesMap.put(zipEntryJar.getName().replace(".class", StringPool.BLANK), new ConfigurationClassData(jarIs));
                                }

                                zipEntryJar = jarIs.getNextEntry();
                            }
                        }
                        catch (Exception e) {
                            System.out.println("Unable to read the content of " + absolutePath);

                            return;
                        }
                    }
                    else if (absolutePath.endsWith(".lpkg")) {
                        ZipFile zipFile = new ZipFile(absolutePath);

                        Enumeration enu = zipFile.entries();

                        while(enu.hasMoreElements()) {
                            ZipEntry zipEntry = (ZipEntry) enu.nextElement();

                            if (isLiferayJar(zipEntry.getName())) {
                                try (JarInputStream jarIs = new JarInputStream(zipFile.getInputStream(zipEntry))) {
                                    ZipEntry zipEntryJar = jarIs.getNextEntry();

                                    while (zipEntryJar != null) {
                                        if (zipEntryJar.getName().endsWith("Configuration.class")) {
                                            configClassesMap.put(zipEntryJar.getName().replace(".class", StringPool.BLANK), new ConfigurationClassData(jarIs));
                                        }

                                        zipEntryJar = jarIs.getNextEntry();
                                    }
                                }
                                catch (Exception e) {
                                    continue;
                                }
                            }
                        }
                    }
                }
                catch (Exception e) {
                    System.out.println("Unable to get portlet properties");

                    e.printStackTrace();

                    return;
                }
            });


        List<Pair<String, String[]>> configurationProperties = getConfigurationProperties(configClassesMap);

        SortedMap<String, SortedMap<String, String>> foundedProperties = new TreeMap<>();

        for (String property : properties) {
            SortedMap<String, String> mostLikelyMatches = getMostLikelyMatches(property, configurationProperties, getPortletNames(property));

            if (mostLikelyMatches.size() != 0) {
                foundedProperties.put(property, mostLikelyMatches);
            }
        }

        if (foundedProperties.size() != 0) {
            _outputFile.println("Properties moved to OSGI configuration:");

            for (SortedMap.Entry<String, SortedMap<String, String>> entry : foundedProperties.entrySet()) {
                String foundedProperty = entry.getKey();

                _outputFile.print("\t");
                _outputFile.println(foundedProperty + " can match with the following OSGI properties:");

                Map<String, String> matches = entry.getValue();

                for (Map.Entry<String, String> match : matches.entrySet()) {
                    String path = match.getValue();

                    String configFileName = StringUtil.replace(path, StringPool.FORWARD_SLASH.charAt(0), StringPool.PERIOD.charAt(0));

                    _outputFile.print("\t\t");
                    _outputFile.println(match.getKey() +  " from " +  configFileName);
                }

                properties.remove(foundedProperty);
            }
        }

        return properties;
    }

    protected static List<Pair<String, String[]>> getConfigurationProperties(Map<String, ConfigurationClassData> configClassesMap) {
        List<Pair<String, String[]>> configurationProperties = new ArrayList<>();

        for (Map.Entry<String, ConfigurationClassData> configClass : configClassesMap.entrySet()) {
            String className = configClass.getKey();
            ConfigurationClassData configClassData = configClass.getValue();

            String[] allConfigFields = addConfigurationPropertiesByHeritance(configClassData.getSuperClass(), configClassData.getConfigFields(), configClassesMap);


            if (allConfigFields.length > 0) {
                configurationProperties.add(new Pair<>(className, allConfigFields));
            }
        }

        return configurationProperties;
    }

    protected static String[] addConfigurationPropertiesByHeritance(String superClass, String[] configFields, Map<String, ConfigurationClassData> configClassesMap) {
        if ((!superClass.equals("java/lang/Object"))) {
            ConfigurationClassData superClassData = configClassesMap.get(superClass);

            String[] superConfigFields = new String[0];

            if (superClassData != null) {
                superConfigFields = addConfigurationPropertiesByHeritance(superClassData.getSuperClass(), superClassData.getConfigFields(), configClassesMap);
            }

            return ArrayUtil.append(configFields, superConfigFields);
        }

        return configFields;
    }

    protected static SortedMap<String, String> getMostLikelyMatches(String property, List<Pair<String, String[]>> matches, String[] portletName) {
        SortedMap<String, String> mostLikelyMatches = new TreeMap();

        //Default min occurrences to match
        int maxOccurrences = 2;

        for (Pair<String, String[]> match : matches) {
            for (String matchProperty : match.second) {
                if (match(property, matchProperty, match.first, maxOccurrences, portletName)) {
                    int occurrences = getOccurrences(property, match.first);

                    if (occurrences > maxOccurrences) {
                        mostLikelyMatches.clear();

                        maxOccurrences = occurrences;
                    }

                    mostLikelyMatches.put(match.first, matchProperty);
                }
            }
        }

        if (mostLikelyMatches.size() > 1) {
            SortedMap<String, String> theMostLikelyMatches = new TreeMap();

            for (Map.Entry<String, String> match : mostLikelyMatches.entrySet()) {
                if (matchSuffix(property, match.getKey())) {
                    theMostLikelyMatches.put(match.getKey(), match.getValue());
                }
            }

            if (theMostLikelyMatches.size() > 0) {
                mostLikelyMatches = theMostLikelyMatches;
            }
        }

        return mostLikelyMatches;
    }

    protected static String getEquivalence(String portletName) {
        String equivalence = _portletNameEquivalences.get(portletName);

        if (equivalence != null) {
            return equivalence;
        }

        return portletName;
    }

    protected static int getOccurrences(String originalProperty, String property) {
        if (!property.contains(StringPool.PERIOD)) {
            //Camel case property
            property = CamelCaseUtil.fromCamelCase(property, StringPool.PERIOD.charAt(0));
        }

        String[] propertyWords = StringUtil.split(property, StringPool.PERIOD);

        String[] originalPropertyWords = StringUtil.split(originalProperty, StringPool.PERIOD);
        List<String> originalPropertyWordsList = ListUtil.fromArray(originalPropertyWords);

        int numOccurrences = 0;

        for (String word : propertyWords) {
            if (originalPropertyWordsList.contains(word)) {numOccurrences++;}
        }

        return numOccurrences;
    }

    /*
        We get portlet names from first two words in a property
     */
    protected static String[] getPortletNames(String property) {
        String[] portletNames = new String[0];

        int index = 0;

        while ((portletNames.length < 2) && (index != -1)) {
            index = property.indexOf(StringPool.PERIOD);

            String portletName;

            if (index == -1) {
                portletName = property;
            }
            else {
                portletName = property.substring(0, index);

                property = property.substring(index + 1);
            }

            portletNames = ArrayUtil.append(portletNames, portletName);
        }

        return portletNames;
    }

    protected static boolean isLiferayJar(String path) {
        if ((!path.endsWith(".jar")) || (!path.contains("com.liferay"))) {
            return false;
        }

        return true;
    }

    protected static boolean match(String originalProperty, String newProperty, String newPropertyPath, int minOccurrences, String[] portletNames) {
        boolean found = false;

        for (String portletName : portletNames) {
            portletName = getEquivalence(portletName);

            if (portletName != null) {
                if (newPropertyPath.contains(portletName)) {
                    found = true;

                    break;
                }
            }
        }

        if (!found) {
            return false;
        }

        String originalPropertyWithoutPrefix = removeCommonPrefix(originalProperty);

        int numOccurrences = getOccurrences(originalPropertyWithoutPrefix, newProperty);

        if ((numOccurrences == 0) || (numOccurrences < minOccurrences)) {
            return false;
        }

        return true;
    }

    protected static boolean matchSuffix(String originalProperty, String property) {
        if (!property.contains(StringPool.PERIOD)) {
            //Camel case property
            property = CamelCaseUtil.fromCamelCase(property, StringPool.PERIOD.charAt(0));
        }

        String[] propertyWords = StringUtil.split(property, StringPool.PERIOD);

        String propertySuffix = propertyWords[propertyWords.length-2] + StringPool.PERIOD + propertyWords[propertyWords.length-1];

        if (originalProperty.endsWith(propertySuffix)) {
            return true;
        }
        else {
            return false;
        }
    }

    protected static SortedSet<String> manageExceptions(SortedSet<String> properties) {
        Set<String> removedProperties = new HashSet<String>();
        SortedSet<String> informationToPrint = new TreeSet<String>();

        for (String property : properties) {
            if (property.endsWith("display.templates.config") && !property.equals("blogs.display.templates.config") && !property.equals("dl.display.templates.config")) {
                removedProperties.add(property);

                informationToPrint.add(property + " does not exist anymore. OverWrite the method in the ADT handler. See LPS-67466");
            }
        }

        if (removedProperties.size() > 0) {
            _outputFile.println("Following portal properties present an exception:");

            for (String information : informationToPrint) {
                _outputFile.print("\t");
                _outputFile.println(information);
            }

            properties.removeAll(removedProperties);
        }

        return properties;
    }

    protected static String removeCommonPrefix(String property) {
        for (String prefix : _COMMON_PREFIXES) {
            if (property.startsWith(prefix)) {
                property = property.replace(prefix, StringPool.BLANK);

                break;
            }
        }

        return property;
    }

    protected static void printProperties(Set<String> properties) {
        for (String property : properties) {
            _outputFile.print("\t");
            _outputFile.println(property);
        }
    }

    protected static void printUnderline(String text) {
        for (int i=0;i<text.length();i++){
            _outputFile.print(StringPool.DASH);
        }

        _outputFile.println(StringPool.BLANK);
    }

    private static PrintWriter _outputFile;

    private static final String _PORTAL_IMPL_RELATIVE_PATH = "/WEB-INF/lib/portal-impl.jar";

    private static final String[] _COMMON_PREFIXES = new String[] {
        "asset", "dynamic.data.lists", "dynamic.data.mapping", "journal", "audit", "auth", "blogs", "bookmarks", "cas", "journal", "wiki"
    };

    private static final Map<String, String> _portletNameEquivalences;
    static
    {
        _portletNameEquivalences = new HashMap<String, String>();
        _portletNameEquivalences.put("dl", "document-library");
    }

    static class Pair<F, S> {
        final F first;
        final S second;

        public Pair(final F l, final S r) {
            first = l;
            second = r;
        }

        @Override
        public String toString() {
            return "(" + first + ", " + second + ")";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((first == null) ? 0 : first.hashCode());
            result = prime * result + ((second == null) ? 0 : second.hashCode());
            return result;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Pair<?, ?> other = (Pair<?, ?>) obj;
            if (first == null) {
                if (other.first != null) {
                    return false;
                }
            } else if (!first.equals(other.first)) {
                return false;
            }
            if (second == null) {
                if (other.second != null) {
                    return false;
                }
            } else if (!second.equals(other.second)) {
                return false;
            }
            return true;
        }
    }
}
