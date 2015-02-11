package uk.co.thefishlive.icons;

import com.google.common.collect.Maps;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import uk.co.thefishlive.icons.css.CssElementList;
import uk.co.thefishlive.icons.css.CssException;
import uk.co.thefishlive.icons.css.CssParser;

@Mojo(name = "icons-generate", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class IconsGeneratorMojo extends AbstractMojo {

    @Component
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    @Parameter(property = "mappings")
    private Map<String, String> mappings = Maps.newHashMap();

    @Parameter(property = "icon-directories", required = true)
    private List<String> iconDirectories;

    @Parameter(property = "ui-directories", required = true)
    private List<String> uiDirectories;

    @Parameter(property = "output-file", required = true)
    private File outputFile;

    @Parameter(property = "pretty-print")
    private boolean pretty;

    private Map<IconData, String> icons = Maps.newHashMap();
    private Map<IconData, String> used = Maps.newHashMap();
    private Map<String, Document> uis = Maps.newHashMap();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Scanning icon directories");

        for (String path : iconDirectories) {
            File dir = new File(path);
            getLog().debug("Directory: " + path);

            if (!dir.exists() && dir.isDirectory()) {
                throw new MojoExecutionException("Directory " + path + " is not a readable directory");
            }

            scanIconDirectories(dir, "");
        }

        getLog().info("Scanning ui directories");

        for (String path : uiDirectories) {
            File dir = new File(path);
            getLog().debug("Directory: " + path);

            if (!dir.exists() && dir.isDirectory()) {
                throw new MojoExecutionException("Directory " + path + " is not a readable directory");
            }

            scanUiDirectories(dir, "");
        }

        getLog().info("Processing ui files");
        CssParser parser = new CssParser();

        for (Map.Entry<String, Document> entry : uis.entrySet()) {
            Document document = entry.getValue();
            document.getDocumentElement().normalize();

            NodeList images = document.getElementsByTagName("ImageView");

            for (int i = 0; i < images.getLength(); i++) {
                Node node = images.item(i);
                NamedNodeMap attributes = node.getAttributes();

                try {
                    Node styleNode = attributes.getNamedItem("style");

                    if (styleNode == null) {
                        continue;
                    }

                    CssElementList style = parser.parseStyleString(styleNode.getNodeValue());

                    if (style.contains("icon")) {
                        IconData data = new IconData(style);

                        if (icons.containsKey(data)) {
                            getLog().debug("Icon " + data + " used");
                            used.put(data, icons.get(data));
                        } else {
                            getLog().warn("Could not find icon for data " + data);
                        }
                    }
                } catch (CssException e) {
                    throw new MojoExecutionException("Could not parse css string " + attributes.getNamedItem("style"), e);
                }
            }
        }

        getLog().info("Creating icons config");

        GsonBuilder gsonBuilder = new GsonBuilder();

        if (pretty) {
            gsonBuilder.setPrettyPrinting();
        }

        Gson gson = gsonBuilder.create();

        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();

        for (Map.Entry<IconData, String> entry : used.entrySet()) {
            JsonObject icon = new JsonObject();

            JsonObject iconData = new JsonObject();
            iconData.addProperty("name", entry.getKey().getId());
            iconData.addProperty("colour", entry.getKey().getColor());
            iconData.addProperty("size", entry.getKey().getSize());

            icon.add("icon", iconData);
            icon.addProperty("path", entry.getValue());

            array.add(icon);
        }

        json.add("icons", array);
        outputFile.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(outputFile)) {
            gson.toJson(json, writer);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not write output file", e);
        }

        getLog().info("Found " + icons.size() + " icons");
    }

    private void scanUiDirectories(File dir, String path) throws MojoExecutionException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                scanUiDirectories(file, path + file.getName() + File.separator);
                continue;
            }

            try {
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(file);

                uis.put(path + file.getName(), document);
                getLog().debug(path + file.getName());
            } catch (ParserConfigurationException | SAXException | IOException e) {
                throw new MojoExecutionException("Could not load xml file " + file.getName(), e);
            }
        }
    }

    private void scanIconDirectories(File dir, String path) {
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                scanIconDirectories(file, path + file.getName() + File.separator);
                continue;
            }

            String name = file.getName().substring(3, file.getName().lastIndexOf('.'));
            icons.put(parseName(name), path + file.getName());
            getLog().debug(parseName(name).toString());
        }
    }

    private IconData parseName(String name) {
        String size = name.substring(name.lastIndexOf('_') + 1);
        name = name.substring(0, name.lastIndexOf('_'));
        String colour = name.substring(name.lastIndexOf('_') + 1);
        name = name.substring(0, name.lastIndexOf('_'));

        if (mappings.containsKey(name)) {
            name = mappings.get(name);
        }

        return new IconData(name, colour, size);
    }
}
