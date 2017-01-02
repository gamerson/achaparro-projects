package com.liferay.upgrade;

import com.liferay.portal.kernel.util.*;
import javafx.util.Pair;

import java.io.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Created by achaparro on 28/12/16.
 */
public class PropertiesChecker {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Please, specify at least the following arguments: ");
            System.out.println("URL to old portal-ext.properties");
            System.out.println("URL to the code where the program should check");

            return;
        }

        String oldPropertiesFileURL = args[0];
        String sourceCodeURL = args[1];

        _outputFile = generateOutputFile();

        try {
            Properties oldProperties = getProperties(oldPropertiesFileURL);

            String newPropertiesFileURL = sourceCodeURL + PORTAL_PROPERTIES_RELATIVE_PATH;
            Properties newProperties = getProperties(newPropertiesFileURL);

            SortedSet<String> removedProperties = checkProperties(oldProperties, newProperties);

            removedProperties = checkPortletProperties(removedProperties, sourceCodeURL);

            removedProperties = checkConfigurationProperties(removedProperties, sourceCodeURL);

            _outputFile.println();
            _outputFile.println("We haven't found a new property for the following old properties:");

            for (String property : removedProperties) {
                _outputFile.print("\t");
                _outputFile.println(property);
            }

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

    protected static SortedSet<String> checkProperties(Properties oldProperties, Properties newProperties) {
        SortedSet<String> removedProperties = new TreeSet<String>();

        Enumeration enuKeys = oldProperties.keys();

        while (enuKeys.hasMoreElements()) {
            String key = enuKeys.nextElement().toString();

            if (newProperties.getProperty(key) == null) {
                removedProperties.add(key);
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

        _outputFile.println("Some properties have been moved to portlet.properties: ");

        Set<String> foundProperties = new HashSet<>();

        for (String property : properties) {
            String value = portletsProperties.get(property);

            if (value != null) {
                foundProperties.add(property);

                _outputFile.print("\t");
                _outputFile.println("Property " + property + " moved to " +  value);
            }
        }

        properties.removeAll(foundProperties);

        return properties;
    }

    protected static SortedSet<String> checkConfigurationProperties(SortedSet<String> properties, String sourceCodeURL) throws IOException {
        Set<Pair<String, String>> configurationProperties = new HashSet<Pair<String, String>>();

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

                                        Pair pair = new Pair(configurationProperty, absolutePath);

                                        configurationProperties.add(pair);
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

        SortedMap<String, Map<String, String>> foundedProperties = new TreeMap<>();

        for (String property : properties) {
            Map<String, String> matches = new HashMap<>();

            for (Pair<String, String> configurationProperty : configurationProperties) {
                String configurationPropertyName = (String) configurationProperty.getKey();

                String configurationPropertyNameAsPortalProperty = CamelCaseUtil.fromCamelCase(configurationPropertyName, StringPool.PERIOD.charAt(0));

                boolean exit = false;

                while (!exit) {
                    if (property.contains(configurationPropertyNameAsPortalProperty)) {
                        matches.put(configurationPropertyName, configurationProperty.getValue());

                        break;
                    }
                    else {
                        int index = configurationPropertyNameAsPortalProperty.lastIndexOf(StringPool.PERIOD);

                        if (index == -1) {
                            exit = true;
                        }
                        else {
                            configurationPropertyNameAsPortalProperty = configurationPropertyNameAsPortalProperty.substring(0, index);
                        }
                    }
                }
            }

            if (matches.size() != 0) {
                foundedProperties.put(property, matches);
            }
        }

        if (foundedProperties.size() != 0) {
            _outputFile.println();
            _outputFile.println("Properties moved to OSGI configuration:");

            for (Map.Entry<String, Map<String, String>> entry : foundedProperties.entrySet()) {
                String foundedProperty = entry.getKey();

                _outputFile.print("\t");
                _outputFile.println(foundedProperty + " can match with the following OSGI properties :");

                Map<String, String> matches = entry.getValue();

                String[] mostLikelyMatches = getMostLikelyMatches(foundedProperty, matches.keySet());

                for (String mostLikelyMatch : mostLikelyMatches) {
                    String path = matches.get(mostLikelyMatch);

                    int index = path.lastIndexOf("com/liferay/");

                    String configFilePath = path.substring(index);

                    String configFileName = configFilePath.replace(".java", StringPool.BLANK);

                    configFileName = StringUtil.replace(configFileName, StringPool.FORWARD_SLASH.charAt(0), StringPool.PERIOD.charAt(0));

                    _outputFile.print("\t\t");
                    _outputFile.println(mostLikelyMatch +  " from " +  configFileName);
                }

                properties.remove(foundedProperty);
            }
        }

        return properties;
    }

    protected static String[] getMostLikelyMatches(String property, Set<String> matches) {
        List<String> propertyWords = Arrays.asList(StringUtil.split(property, StringPool.PERIOD));

        List<String> mostLikelyMatches = new ArrayList<>();

        int maxOccurrences = 0;

        for (String match : matches) {
            String matchAsPortalProperty = CamelCaseUtil.fromCamelCase(match, StringPool.PERIOD.charAt(0));

            String[] matchWords = StringUtil.split(matchAsPortalProperty, StringPool.PERIOD);

            int occurrences = 0;

            for (String word : matchWords) {
                if (propertyWords.contains(word)) {
                    occurrences++;
                }
            }

            if (occurrences > maxOccurrences) {
                mostLikelyMatches.clear();
                mostLikelyMatches.add(match);

                maxOccurrences = occurrences;
            }
            else if (occurrences == maxOccurrences) {
                mostLikelyMatches.add(match);
            }
        }

        return mostLikelyMatches.toArray(new String[mostLikelyMatches.size()]);
    }

    private static PrintWriter _outputFile;

    private static final String PORTAL_PROPERTIES_RELATIVE_PATH = "/portal-impl/classes/portal.properties";
}
