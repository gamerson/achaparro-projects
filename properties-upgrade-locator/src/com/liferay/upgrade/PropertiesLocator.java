package com.liferay.upgrade;

import com.liferay.portal.kernel.util.*;

import java.io.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Created by achaparro on 28/12/16.
 */
public class PropertiesLocator {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Please, specify the following arguments: ");
            System.out.println("URL to old portal-ext.properties");
            System.out.println("URL to the code where the program should check");

            return;
        }

        String oldPropertiesFileURL = args[0];
        String sourceCodeURL = args[1];

        _outputFile = generateOutputFile();

        String title = "Checking the location for old properties in the new version";

        _outputFile.println(title);
        printUnderline(title);

        try {
            Properties oldProperties = getProperties(oldPropertiesFileURL);

            String newPropertiesFileURL = sourceCodeURL + _PORTAL_PROPERTIES_RELATIVE_PATH;
            Properties newProperties = getProperties(newPropertiesFileURL);

            SortedSet<String> remainedProperties = new TreeSet<String>();

            SortedSet<String> removedProperties = getRemovedProperties(oldProperties, newProperties, remainedProperties);

            removedProperties = manageExceptions(removedProperties);

            _outputFile.println();
            removedProperties = checkPortletProperties(removedProperties, sourceCodeURL);

            _outputFile.println();
            removedProperties = checkConfigurationProperties(removedProperties, sourceCodeURL);

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

    protected static SortedSet<String> checkPortletProperties(SortedSet<String> properties, String sourceCodeURL) throws Exception {
        List<Pair<String, String>> portletsProperties = new ArrayList<>();

        Files.walk(Paths.get(sourceCodeURL))
            .filter(p -> p.toFile().getAbsolutePath().endsWith("/src/main/resources/portlet.properties"))
            .forEach(p -> {
                try {
                    String absolutePath = p.toFile().getAbsolutePath();

                    Properties portletProperties = getProperties(absolutePath);

                    Enumeration enuKeys = portletProperties.keys();

                    while (enuKeys.hasMoreElements()) {
                        portletsProperties.add(new Pair<String, String>((String) enuKeys.nextElement(), absolutePath));
                    }
                }
                catch (Exception e) {
                    System.out.println("Unable to get portlet properties");

                    return;
                }
            });

        SortedMap<String, SortedMap<String, String>> foundedProperties = new TreeMap<>();

        for (String property : properties) {
            SortedMap<String, String> mostLikelyMatches = getMostLikelyMatches(property, portletsProperties, getPortletName(property));

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

    protected static SortedSet<String> checkConfigurationProperties(SortedSet<String> properties, String sourceCodeURL) throws IOException {
        List<Pair<String, String>> configurationProperties = new ArrayList<>();

        Files.walk(Paths.get(sourceCodeURL))
            .filter(p -> p.getFileName().toString().endsWith("Configuration.java"))
            .forEach(p -> {
                try {
                    String absolutePath = p.toFile().getAbsolutePath();

                    BufferedReader in = new BufferedReader(new FileReader(absolutePath));

                    String line = null;
                    while((line = in.readLine()) != null) {
                        if (line.contains("@Meta.AD")) {
                            while((line = in.readLine()) != null) {
                                if (line.contains("public")) {
                                    int endIndex = line.lastIndexOf("();");
                                    int lastSpaceIndex = line.lastIndexOf(" ");

                                    if ((endIndex != -1) && (lastSpaceIndex != -1)) {
                                        String configurationProperty = line.substring(lastSpaceIndex, endIndex).trim();

                                        configurationProperties.add(new Pair<String, String>(configurationProperty, absolutePath));
                                    }

                                    break;
                                }
                            }
                        }
                    }

                }
                catch (Exception e) {
                    System.out.println("Unable to get configuration properties");

                    return;
                }
            });

        SortedMap<String, SortedMap<String, String>> foundedProperties = new TreeMap<>();

        for (String property : properties) {
            SortedMap<String, String> mostLikelyMatches = getMostLikelyMatches(property, configurationProperties, getPortletName(property));

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

                    int index = path.lastIndexOf("com/liferay/");

                    String configFilePath = path.substring(index);

                    String configFileName = configFilePath.replace(".java", StringPool.BLANK);

                    configFileName = StringUtil.replace(configFileName, StringPool.FORWARD_SLASH.charAt(0), StringPool.PERIOD.charAt(0));

                    _outputFile.print("\t\t");
                    _outputFile.println(match.getKey() +  " from " +  configFileName);
                }

                properties.remove(foundedProperty);
            }
        }

        return properties;
    }

    protected static SortedMap<String, String> getMostLikelyMatches(String property, List<Pair<String, String>> matches, String portletName) {
        SortedMap<String, String> mostLikelyMatches = new TreeMap();

        //Default min occurrences to match
        int maxOccurrences = 2;

        for (Pair<String, String> match : matches) {
            if (match(property, match, maxOccurrences, portletName)) {
                int occurrences = getOccurrences(property, match.first);

                if (occurrences > maxOccurrences) {
                    mostLikelyMatches.clear();

                    maxOccurrences = occurrences;
                }

                mostLikelyMatches.put(match.first, match.second);
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

    protected static String getPortletName(String property) {
        int index = property.indexOf(StringPool.PERIOD);

        return property.substring(0, index);
    }

    protected static boolean match(String originalProperty, Pair<String, String> property, int minOccurrences, String portletName) {
        String propertyPath = property.second;

        portletName = getEquivalence(portletName);

        if (portletName != null) {
            if (!propertyPath.contains(portletName)) {return false;}
        }

        String originalPropertyWithoutPrefix = removeCommonPrefix(originalProperty);

        String propertyName = property.first;

        int numOccurrences = getOccurrences(originalPropertyWithoutPrefix, propertyName);

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

    private static final String _PORTAL_PROPERTIES_RELATIVE_PATH = "/portal-impl/src/portal.properties";

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
