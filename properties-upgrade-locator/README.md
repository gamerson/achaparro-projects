# Liferay Properties Locator
This tool will help you to know where to set you old portal-ext.properties in Liferay 7/DXP before doing the upgrade.

To execute the application you need:
- Configure the classpath (portal-kernel.jar library is needed)

- To pass the following parameters to the main class:
    - Previous portal-ext.properties
    - Path to current Liferay 7/DXP bundle

For example (from the root of the project):
    java -cp "/home/achaparro/servers/dxp/tomcat-8.0.32/lib/ext/portal-kernel.jar:/home/achaparro/code/projects/properties-upgrade-locator/out/production/properties-upgrade-locator" com.liferay.upgrade.properties.locator.PropertiesLocator /home/achaparro/code/projects/properties-upgrade-locator/resources/6.2-fix-pack-131/portal.properties /home/achaparro/servers/dxp