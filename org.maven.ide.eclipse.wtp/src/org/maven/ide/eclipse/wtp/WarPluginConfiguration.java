/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.wtp;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.war.Overlay;
import org.apache.maven.plugin.war.overlay.InvalidOverlayConfigurationException;
import org.apache.maven.plugin.war.overlay.OverlayManager;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jst.j2ee.internal.J2EEVersionConstants;
import org.eclipse.jst.j2ee.web.project.facet.WebFacetUtils;
import org.eclipse.jst.jee.util.internal.JavaEEQuickPeek;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.eclipse.wst.common.project.facet.core.IProjectFacetVersion;
import org.maven.ide.eclipse.wtp.internal.StringUtils;


/**
 * See http://maven.apache.org/plugins/maven-war-plugin/war-mojo.html
 * 
 * @author Igor Fedorenko
 * @author Fred Bricon
 */
@SuppressWarnings("restriction")
public class WarPluginConfiguration {
  private static final String WAR_SOURCE_FOLDER = "/src/main/webapp";

  private static final String WAR_PACKAGING = "war";

  private static final String WEB_XML = "WEB-INF/web.xml";

  private static final int WEB_3_0_ID = 30;//Same Value as J2EEVersionConstants.WEB_3_0_ID from WTP 3.2 (org.eclipse.jst.j2ee.core_1.2.0.vX.jar)

  final Plugin plugin;

  private IProject project;
  
  private MavenProject mavenProject;

  public WarPluginConfiguration(MavenProject mavenProject, IProject project) {
    this.plugin = mavenProject.getPlugin("org.apache.maven.plugins:maven-war-plugin");
    this.project = project;
    this.mavenProject = mavenProject;
  }

  static boolean isWarProject(MavenProject mavenProject) {
    return WAR_PACKAGING.equals(mavenProject.getPackaging());
  }


  /**
   * @return war plugin configuration or null.
   */
  private Xpp3Dom getConfiguration() {
    if(plugin == null) {
      return null;
    }
    return (Xpp3Dom) plugin.getConfiguration();
  }

 
  public Xpp3Dom[] getWebResources() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom webResources = config.getChild("webResources");
      if (webResources != null && webResources.getChildCount() > 0)
      {
        int count = webResources.getChildCount();  
        Xpp3Dom[] resources = new Xpp3Dom[count];
        for (int i= 0; i< count ; i++) {
          //MECLIPSEWTP-97 support old maven-war-plugin configurations which used <webResource> 
          // instead of <resource>
          Xpp3Dom webResource = webResources.getChild(i);
          if ("resource".equals(webResource.getName())) {
            resources[i] = webResource;
          } else {
            resources[i] = new Xpp3Dom(webResource,"resource");
          }
        }
        return resources;
      }
    }
    return null;
  }

  public String getValueForWebResource(Xpp3Dom dom, String value) {
    Xpp3Dom resource = dom.getChild("resource");
    if(resource != null) {
      Xpp3Dom child = resource.getChild(value);
      if(child != null) {
        return child.getValue();
      }
    }
    return null;
  }

  public String getDirectoryForWebResource(Xpp3Dom dom) {
    return getValueForWebResource(dom, "directory");
  }

  public String getTargetPathForWebResource(Xpp3Dom dom) {
    return getValueForWebResource(dom, "targetPath");
  }

  public String getWarSourceDirectory() {
    Xpp3Dom dom = getConfiguration();
    if(dom == null) {
      return WAR_SOURCE_FOLDER;
    }

    Xpp3Dom[] warSourceDirectory = dom.getChildren("warSourceDirectory");
    if(warSourceDirectory != null && warSourceDirectory.length > 0) {
      // first one wins
      String dir = warSourceDirectory[0].getValue();
      //MNGECLIPSE-1600 fixed absolute warSourceDirectory thanks to Snjezana Peco's patch
      if(project != null) {
        return WTPProjectsUtil.tryProjectRelativePath(project, dir).toOSString();
      }
      return dir;
    }

    return WAR_SOURCE_FOLDER;
  }

  public String[] getPackagingExcludes() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
        Xpp3Dom excl = config.getChild("packagingExcludes");
        if(excl != null) {
          return StringUtils.tokenizeToStringArray(excl.getValue(), ",");
        }
    }
    return new String[0];
  }

  public String[] getPackagingIncludes() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom incl = config.getChild("packagingIncludes");
      if(incl != null && incl.getValue() != null) {
        return StringUtils.tokenizeToStringArray(incl.getValue(), ",");
      }
    }
    return new String[0];
  }

  public boolean isAddManifestClasspath() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom arch = config.getChild("archive");
      if(arch != null) {
        Xpp3Dom manifest = arch.getChild("manifest");
        if(manifest != null) {
          Xpp3Dom addToClp = manifest.getChild("addClasspath");
          if(addToClp != null) {
            return Boolean.valueOf(addToClp.getValue());
          }
        }
      }
  }
    return false;
  }

  public String getManifestClasspathPrefix() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom arch = config.getChild("archive");
      if(arch != null) {
        Xpp3Dom manifest = arch.getChild("manifest");
        if(manifest != null) {
          Xpp3Dom prefix = manifest.getChild("classpathPrefix");
          if(prefix != null && !StringUtils.nullOrEmpty(prefix.getValue())) {
            String rawPrefix = prefix.getValue().trim();
            if (!rawPrefix.endsWith("/")){
              rawPrefix += "/";
            }
            return rawPrefix;
          }
        }
      }
    }
    return null;
  }

  public IProjectFacetVersion getWebFacetVersion(IProject project) {
    IFile webXml;
    String customWebXml = getCustomWebXml(project);
    if (customWebXml == null) {
      webXml = project.getFolder(getWarSourceDirectory()).getFile(WEB_XML);
    } else {
      webXml = project.getFile(customWebXml);
    }

    if(webXml.isAccessible()) {
      try {
        InputStream is = webXml.getContents();
        try {
          JavaEEQuickPeek jqp = new JavaEEQuickPeek(is);
          switch(jqp.getVersion()) {
            case J2EEVersionConstants.WEB_2_2_ID:
              return WebFacetUtils.WEB_22;
            case J2EEVersionConstants.WEB_2_3_ID:
              return WebFacetUtils.WEB_23;
            case J2EEVersionConstants.WEB_2_4_ID:
              return WebFacetUtils.WEB_24;
            case J2EEVersionConstants.WEB_2_5_ID:
              return WebFacetUtils.WEB_FACET.getVersion("2.5");
            //MNGECLIPSE-1978  
            case WEB_3_0_ID://JavaEEQuickPeek will return this value only if WTP version >= 3.2
              return WebFacetUtils.WEB_FACET.getVersion("3.0");//only exists in WTP version >= 3.2
          }
        } finally {
          is.close();
        }
      } catch(IOException ex) {
        // expected
      } catch(CoreException ex) {
        // expected
      }
    }
   
    //MNGECLIPSE-1978 If no web.xml found and the project depends on some java EE 6 jar and WTP >= 3.2, then set web facet to 3.0
    if (WTPProjectsUtil.isJavaEE6Available() && WTPProjectsUtil.hasInClassPath(project, "javax.servlet.annotation.WebServlet")) {
      return WebFacetUtils.WEB_FACET.getVersion("3.0");
    }
    
    //MNGECLIPSE-984 web.xml is optional for 2.5 Web Projects
    return WTPProjectsUtil.DEFAULT_WEB_FACET;
    //We don't want to prevent the project creation when the java compiler level is < 5, we coud try that instead :
    //IProjectFacetVersion javaFv = JavaFacetUtils.compilerLevelToFacet(JavaFacetUtils.getCompilerLevel(project));
    //return (JavaFacetUtils.JAVA_50.compareTo(javaFv) > 0)?WebFacetUtils.WEB_24:WebFacetUtils.WEB_25; 
  }
  /**
   * Get the custom location of web.xml, as set in &lt;webXml&gt;.
   * @return the custom location of web.xml or null if &lt;webXml&gt; is not set
   */
  public String getCustomWebXml(IProject project) {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom webXmlDom = config.getChild("webXml");
      if(webXmlDom != null && webXmlDom.getValue() != null) {
        String webXmlFile = webXmlDom.getValue().trim();
        webXmlFile = ProjectUtils.getRelativePath(project, webXmlFile);
        return webXmlFile;
      }
    }
    return null;
  }

  /**
   * @return
   */
  public List<String> getWebResourcesFilters() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom filtersNode = config.getChild("filters");
      if (filtersNode != null && filtersNode.getChildCount() > 0) {
        List<String> filters = new ArrayList<String>(filtersNode.getChildCount());
        for (Xpp3Dom filterNode : filtersNode.getChildren("filter")) {
          String  filter = filterNode.getValue();
          if (!StringUtils.nullOrEmpty(filter)) {
            filters.add(filter);
          }
        }
        return filters;
      }
    }
    return Collections.emptyList();
  }

  /**
   * @return
   * @throws CoreException 
   */
  public List<Overlay> getOverlays() throws CoreException {
    Overlay currentProjectOverlay = Overlay.createInstance();
    currentProjectOverlay.setArtifact(mavenProject.getArtifact());
    OverlayManager overlayManager = null;
    List<Overlay> overlays = null;
    try {
      overlayManager = new OverlayManager(getConfiguredOverlays(), 
                                                         mavenProject, 
                                                         getDependentWarIncludes(),
                                                         getDependentWarExcludes(), 
                                                         currentProjectOverlay);
      overlays = overlayManager.getOverlays();
    } catch(InvalidOverlayConfigurationException ex) {
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, ex.getMessage(),ex));
    }
    
    return overlays;
  }
  
  public String getDependentWarIncludes() {
    return DomUtils.getChildValue(getConfiguration(), "dependentWarIncludes", "**/**");
  }

  public String getDependentWarExcludes() {
    return DomUtils.getChildValue(getConfiguration(), "dependentWarExcludes", "META-INF/MANIFEST.MF");
  }

  
  public List<Overlay> getConfiguredOverlays() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom overlaysNode = config.getChild("overlays");
      if (overlaysNode != null && overlaysNode.getChildCount() > 0) {
        List<Overlay> overlays = new ArrayList<Overlay>(overlaysNode.getChildCount());
        for (Xpp3Dom overlayNode : overlaysNode.getChildren("overlay")) {
          overlays.add(parseOverlay(overlayNode));
        }
        return overlays;
      }
    }
    return Collections.emptyList();
  }
  
  /**
   * @param overlayNode
   * @return
   */
  private Overlay parseOverlay(Xpp3Dom overlayNode) {
    String artifactId = DomUtils.getChildValue(overlayNode, "artifactId");
    String groupId = DomUtils.getChildValue(overlayNode, "groupId");
    String exclusions = DomUtils.getChildValue(overlayNode, "exclusions");
    String inclusions = DomUtils.getChildValue(overlayNode, "inclusions");
    String classifier = DomUtils.getChildValue(overlayNode, "classifier");
    boolean filtered = DomUtils.getBooleanChildValue(overlayNode, "filtered");
    boolean skip = DomUtils.getBooleanChildValue(overlayNode, "skip");
    String type = DomUtils.getChildValue(overlayNode, "type", "war");
    String targetPath = DomUtils.getChildValue(overlayNode, "targetPath", "/");

    Overlay overlay = new Overlay();
    overlay.setArtifactId(artifactId);
    overlay.setGroupId(groupId);
    overlay.setClassifier(classifier);
    if (StringUtils.nullOrEmpty(exclusions)) {
      overlay.setExcludes(getDependentWarExcludes());
    } else {
      overlay.setExcludes(exclusions);
    }
    if (StringUtils.nullOrEmpty(inclusions)) {
      overlay.setIncludes(getDependentWarIncludes());
    } else {
      overlay.setIncludes(inclusions);
    }
    overlay.setFiltered(filtered);
    overlay.setSkip(skip);
    overlay.setTargetPath(targetPath);
    overlay.setType(type);
    
    return overlay;
  }

  public String getEscapeString() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      DomUtils.getChildValue(config, "escapeString");
    }
    return null;
  }

  public Xpp3Dom[] getNonfilteredExtensions() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom extensionsNode = config.getChild("nonFilteredFileExtensions");
      if (extensionsNode != null && extensionsNode.getChildCount() > 0) {
        return extensionsNode.getChildren();
      }
    }
    return null;
  }

}
