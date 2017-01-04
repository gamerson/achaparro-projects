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

            String newPropertiesFileURL = sourceCodeURL + PORTAL_PROPERTIES_RELATIVE_PATH;
            Properties newProperties = getProperties(newPropertiesFileURL);

            SortedSet<String> remainedProperties = new TreeSet<String>();

            SortedSet<String> removedProperties = getRemovedProperties(oldProperties, newProperties, remainedProperties);

            removedProperties = manageExceptions(removedProperties);

            _outputFile.println();
            removedProperties = checkPortletProperties(removedProperties, sourceCodeURL);

            _outputFile.println();
            removedProperties = checkConfigurationProperties(removedProperties, sourceCodeURL);

            // Maybe it's better to say that the rest of the properties still remain in the new portal.properties
            _outputFile.println();
            _outputFile.println("The following properties still exist in the new portal.properties:");
            printProperties(remainedProperties);

            _outputFile.println();
            _outputFile.println("We haven't found a new property for the following old properties (check if you still need them or check the documentation to find a replacement):");
            printProperties(removedProperties);

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
        Map<String, String> portletsProperties = new HashMap<>();

        Files.walk(Paths.get(sourceCodeURL))
            .filter(p -> p.toFile().getAbsolutePath().endsWith("/src/main/resources/portlet.properties"))
            .forEach(p -> {
                try {
                    String absolutePath = p.toFile().getAbsolutePath();

                    Properties portletProperties = getProperties(absolutePath);

                    Enumeration enuKeys = portletProperties.keys();

                    while (enuKeys.hasMoreElements()) {
                        portletsProperties.put((String) enuKeys.nextElement(), absolutePath);
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
        Map<String, String> configurationProperties = new HashMap<>();

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

                                        configurationProperties.put(configurationProperty, absolutePath);
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

    protected static SortedMap<String, String> getMostLikelyMatches(String property, Map<String, String> matches, String portletName) {
        SortedMap<String, String> mostLikelyMatches = new TreeMap();

        //Default min occurrences to match
        int maxOccurrences = 2;

        for (Map.Entry<String, String> match : matches.entrySet()) {
            if (match(property, match, maxOccurrences, portletName)) {
                int occurrences = getOccurrences(property, match.getKey());

                if (occurrences > maxOccurrences) {
                    mostLikelyMatches.clear();

                    maxOccurrences = occurrences;
                }

                mostLikelyMatches.put(match.getKey(), match.getValue());
            }
        }

        return mostLikelyMatches;
    }

    protected static int getOccurrences(String originalProperty, String property) {
        if (!property.contains(StringPool.PERIOD)) {
            //Camel case property
            property = CamelCaseUtil.fromCamelCase(property, StringPool.PERIOD.charAt(0));
        }

        String[] propertyWords = StringUtil.split(property, StringPool.PERIOD);

        int numOccurrences = 0;

        for (String word : propertyWords) {
            if (originalProperty.contains(word)) {numOccurrences++;}
        }

        return numOccurrences;
    }

    protected static String getPortletName(String property) {
        int index = property.indexOf(StringPool.PERIOD);

        return property.substring(0, index);
    }

    protected static boolean match(String originalProperty, Map.Entry<String, String> property, int minOccurrences, String portletName) {
        String propertyPath = property.getValue();

        if (portletName != null) {
            if (!propertyPath.contains(portletName)) {return false;}
        }

        String propertyName = property.getKey();

        int numOccurrences = getOccurrences(originalProperty, propertyName);

        if ((numOccurrences == 0) || (numOccurrences < minOccurrences)) {
            return false;
        }

        return true;
    }

    protected static SortedSet<String> manageExceptions(SortedSet<String> properties) {
        Set<String> removedProperties = new HashSet<String>();
        SortedSet<String> informationToPrint = new TreeSet<String>();

        for (String property : properties) {
            if (property.endsWith("display.templates.config")) {
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

    private static final String PORTAL_PROPERTIES_RELATIVE_PATH = "/portal-impl/src/portal.properties";
}
