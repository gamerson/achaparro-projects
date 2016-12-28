package com.liferay.upgrade;

import com.liferay.portal.kernel.util.CamelCaseUtil;
import com.liferay.portal.kernel.util.StringPool;
import javafx.util.Pair;

import java.io.*;
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
                _outputFile.println(property);
            }

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

        _outputFile.println("- Properties not found in the new portal properties:");

        while (enuKeys.hasMoreElements()) {
            String key = enuKeys.nextElement().toString();

            if (newProperties.getProperty(key) == null) {
                removedProperties.add(key);
            }
        }

        for (String property : removedProperties) {
            _outputFile.println(property);
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

        _outputFile.println("- Some of those properties have been moved to portlet.properties: ");

        Set<String> foundProperties = new HashSet<>();

        for (String property : properties) {
            String value = portletsProperties.get(property);

            if (value != null) {
                foundProperties.add(property);

                _outputFile.println("Property " + property + " moved to " +  value);
            }
        }

        properties.removeAll(foundProperties);

        return properties;
    }

    protected static SortedSet<String> checkConfigurationProperties(SortedSet<String> properties, String sourceCodeURL) throws IOException {
        Set<Pair> configurationProperties = new HashSet<Pair>();

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

        Set<String> foundProperties = new HashSet<>();
        SortedSet<String> informationToPrint = new TreeSet<>();

        for (Pair configurationProperty : configurationProperties) {
            for (String property : properties) {
                String configurationPropertyName = (String)configurationProperty.getKey();

                String configurationPropertyNameAsPortalProperty= CamelCaseUtil.fromCamelCase(configurationPropertyName, StringPool.PERIOD.charAt(0));

                if (property.contains(configurationPropertyNameAsPortalProperty)) {
                    foundProperties.add(property);

                    informationToPrint.add("Property " + property + " could be move to OSGI property " + configurationPropertyName + " in " + configurationProperty.getValue());
                }
            }

        }

        _outputFile.println();
        _outputFile.println("Properties moved to OSGI configuration:");

        for (String information : informationToPrint) {
            _outputFile.println(information);
        }

        properties.removeAll(foundProperties);

        return properties;
    }

    private static PrintWriter _outputFile;

    private static final String PORTAL_PROPERTIES_RELATIVE_PATH = "/portal-impl/classes/portal.properties";
}
