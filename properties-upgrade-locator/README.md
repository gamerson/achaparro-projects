# Liferay Properties Locator
This tool will help you to know where to set you old portal-ext.properties in Liferay 7/DXP before doing the upgrade.

To execute the application you need:
- Download properties-upgrade-locator.jar (check dist folder)
- Configure the classpath (portal-kernel.jar library is needed)

- To pass the following parameters to the main class:
    - Your previous portal-ext.properties
    - Path to current Liferay 7/DXP bundle

Like this:
- java -cp "{DXP_bundle_path}/tomcat-8.0.32/lib/ext/portal-kernel.jar:properties-upgrade-locator.jar" com.liferay.upgrade.properties.locator.PropertiesLocator {your_old_portat-ext.properties_path} {DXP_bundle_path}

*Remember to use ; instead of : to separate elements in classpath if you use Windows*

For example in Unix:
- java -cp "/home/achaparro/servers/dxp/tomcat-8.0.32/lib/ext/portal-kernel.jar:properties-upgrade-locator.jar" com.liferay.upgrade.properties.locator.PropertiesLocator ../resources/6.2-fix-pack-131/portal.properties /home/achaparro/servers/dxp

## Online use
If you just need to check a few propoerties, I have upload the following file with the execution of the whole portal.properties in 6.2 against to DXP SP 28:
results_with_dxp_fix_pack_28.out