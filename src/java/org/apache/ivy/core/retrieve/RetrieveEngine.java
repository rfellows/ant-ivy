/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivy.core.retrieve;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ivy.core.IvyContext;
import org.apache.ivy.core.IvyPatternHelper;
import org.apache.ivy.core.cache.CacheManager;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DefaultArtifact;
import org.apache.ivy.core.module.id.ModuleId;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.report.XmlReportParser;
import org.apache.ivy.util.FileUtil;
import org.apache.ivy.util.Message;
import org.apache.ivy.util.filter.Filter;
import org.apache.ivy.util.filter.FilterHelper;

public class RetrieveEngine {
	private IvySettings _settings;
	
	public RetrieveEngine(IvySettings settings) {
		_settings = settings;
	}
	
	/**
     * example of destFilePattern :
     * - lib/[organisation]/[module]/[artifact]-[revision].[type]
     * - lib/[artifact].[type] : flatten with no revision
     * moduleId is used with confs and localCacheDirectory to determine
     * an ivy report file, used as input for the copy
     * If such a file does not exist for any conf (resolve has not been called before ?)
     * then an IllegalStateException is thrown and nothing is copied.
     */
    public int retrieve(ModuleId moduleId, String[] confs, final File cache, String destFilePattern) {
        return retrieve(moduleId, confs, cache, destFilePattern, null);
    }
    /**
     * If destIvyPattern is null no ivy files will be copied.
     */
    public int retrieve(ModuleId moduleId, String[] confs, final File cache, String destFilePattern, String destIvyPattern) {
    	return retrieve(moduleId, confs, cache, destFilePattern, destIvyPattern, FilterHelper.NO_FILTER);
    }
    
    public int retrieve(ModuleId moduleId, String[] confs, final File cache, String destFilePattern, String destIvyPattern, Filter artifactFilter) {
    	return retrieve(moduleId, confs, cache, destFilePattern, destIvyPattern, artifactFilter, false, false);
    }
    public int retrieve(ModuleId moduleId, String[] confs, final File cache, String destFilePattern, String destIvyPattern, Filter artifactFilter, boolean sync, boolean useOrigin) {
    	return retrieve(moduleId, confs, cache, destFilePattern, destIvyPattern, artifactFilter, sync, useOrigin, false);
    }
    public int retrieve(ModuleId moduleId, String[] confs, final File cache, String destFilePattern, String destIvyPattern, Filter artifactFilter, boolean sync, boolean useOrigin, boolean makeSymlinks) {
    	if (artifactFilter == null) {
    		artifactFilter = FilterHelper.NO_FILTER;
    	}
    	
        Message.info(":: retrieving :: "+moduleId+(sync?" [sync]":""));
        Message.info("\tconfs: "+Arrays.asList(confs));
        long start = System.currentTimeMillis();
        
        destFilePattern = IvyPatternHelper.substituteVariables(destFilePattern, _settings.getVariables());
        destIvyPattern = IvyPatternHelper.substituteVariables(destIvyPattern, _settings.getVariables());
        CacheManager cacheManager = getCacheManager(cache);
        try {
            Map artifactsToCopy = determineArtifactsToCopy(moduleId, confs, cache, destFilePattern, destIvyPattern, artifactFilter);
            File fileRetrieveRoot = new File(IvyPatternHelper.getTokenRoot(destFilePattern));
            File ivyRetrieveRoot = destIvyPattern == null ? null : new File(IvyPatternHelper.getTokenRoot(destIvyPattern));
            Collection targetArtifactsStructure = new HashSet(); // Set(File) set of all paths which should be present at then end of retrieve (useful for sync) 
            Collection targetIvysStructure = new HashSet(); // same for ivy files
            
            // do retrieve
            int targetsCopied = 0;
            int targetsUpToDate = 0;
            for (Iterator iter = artifactsToCopy.keySet().iterator(); iter.hasNext();) {
                Artifact artifact = (Artifact)iter.next();
                File archive;
				if ("ivy".equals(artifact.getType())) {
					archive = cacheManager.getIvyFileInCache(artifact.getModuleRevisionId());
				} else {
					archive = cacheManager.getArchiveFileInCache(artifact, cacheManager.getSavedArtifactOrigin(artifact), useOrigin);
					if (!useOrigin && !archive.exists()) {
						// file is not available in cache, maybe the last resolve was performed with useOrigin=true.
						// we try to use the best we can
						archive = cacheManager.getArchiveFileInCache(artifact, cacheManager.getSavedArtifactOrigin(artifact));
					}
				}
                Set dest = (Set)artifactsToCopy.get(artifact);
                Message.verbose("\tretrieving "+archive);
                for (Iterator it2 = dest.iterator(); it2.hasNext();) {
                	IvyContext.getContext().checkInterrupted();
                    File destFile = new File((String)it2.next());
                    if (!_settings.isCheckUpToDate() || !upToDate(archive, destFile)) {
                        Message.verbose("\t\tto "+destFile);
                        if (makeSymlinks) {
                            FileUtil.symlink(archive, destFile, null, false);
                        } else {
                            FileUtil.copy(archive, destFile, null);
                        }
                        targetsCopied++;
                    } else {
                        Message.verbose("\t\tto "+destFile+" [NOT REQUIRED]");
                        targetsUpToDate++;
                    }
                    if ("ivy".equals(artifact.getType())) {
                    	targetIvysStructure.addAll(FileUtil.getPathFiles(ivyRetrieveRoot, destFile));
                    } else {
                    	targetArtifactsStructure.addAll(FileUtil.getPathFiles(fileRetrieveRoot, destFile));
                    }
                }
            }
            
            if (sync) {
				Message.verbose("\tsyncing...");
                Collection existingArtifacts = FileUtil.listAll(fileRetrieveRoot);
                Collection existingIvys = ivyRetrieveRoot == null ? null : FileUtil.listAll(ivyRetrieveRoot);

                if (fileRetrieveRoot.equals(ivyRetrieveRoot)) {
                	Collection target = targetArtifactsStructure;
                	target.addAll(targetIvysStructure);
                	Collection existing = existingArtifacts;
                	existing.addAll(existingIvys);
                	sync(target, existing);
                } else {
                	sync(targetArtifactsStructure, existingArtifacts);
                	if (existingIvys != null) {
                		sync(targetIvysStructure, existingIvys);
                	}
                }
            }
            Message.info("\t"+targetsCopied+" artifacts copied, "+targetsUpToDate+" already retrieved");
            Message.verbose("\tretrieve done ("+(System.currentTimeMillis()-start)+"ms)");
            
            return targetsCopied;
        } catch (Exception ex) {
            throw new RuntimeException("problem during retrieve of "+moduleId+": "+ex, ex);
        }
    }

    public CacheManager getCacheManager(File cache) {
    	// TODO reuse instance
		return new CacheManager(_settings, cache);
	}

	private void sync(Collection target, Collection existing) {
		Collection toRemove = new HashSet();
		for (Iterator iter = existing.iterator(); iter.hasNext();) {
			File file = (File) iter.next();
			toRemove.add(file.getAbsoluteFile());
		}
		for (Iterator iter = target.iterator(); iter.hasNext();) {
			File file = (File) iter.next();
			toRemove.remove(file.getAbsoluteFile());
		}
		for (Iterator iter = toRemove.iterator(); iter.hasNext();) {
			File file = (File) iter.next();
			if (file.exists()) {
				Message.verbose("\t\tdeleting "+file);
				FileUtil.forceDelete(file);
			}
		}
	}

	public Map determineArtifactsToCopy(ModuleId moduleId, String[] confs, final File cache, String destFilePattern, String destIvyPattern) throws ParseException, IOException {
    	return determineArtifactsToCopy(moduleId, confs, cache, destFilePattern, destIvyPattern, FilterHelper.NO_FILTER);
    }
    
    public Map determineArtifactsToCopy(ModuleId moduleId, String[] confs, final File cache, String destFilePattern, String destIvyPattern, Filter artifactFilter) throws ParseException, IOException {
        if (artifactFilter == null) {
        	artifactFilter = FilterHelper.NO_FILTER;
        }
        
        // find what we must retrieve where
        final Map artifactsToCopy = new HashMap(); // Artifact source -> Set (String copyDestAbsolutePath)
        final Map conflictsMap = new HashMap(); // String copyDestAbsolutePath -> Set (Artifact source)
        final Map conflictsConfMap = new HashMap(); // String copyDestAbsolutePath -> Set (String conf)
        XmlReportParser parser = new XmlReportParser();
        for (int i = 0; i < confs.length; i++) {
            final String conf = confs[i];
            Collection artifacts = new ArrayList(Arrays.asList(parser.getArtifacts(moduleId, conf, cache)));
            if (destIvyPattern != null) {
                ModuleRevisionId[] mrids = parser.getRealDependencyRevisionIds(moduleId, conf, cache);
                for (int j = 0; j < mrids.length; j++) {
                    artifacts.add(DefaultArtifact.newIvyArtifact(mrids[j], null));
                }
            }
            for (Iterator iter = artifacts.iterator(); iter.hasNext();) {
                Artifact artifact = (Artifact)iter.next();
                String destPattern = "ivy".equals(artifact.getType()) ? destIvyPattern: destFilePattern;
                
                if (!"ivy".equals(artifact.getType()) && !artifactFilter.accept(artifact)) {
                	continue;	// skip this artifact, the filter didn't accept it!
                }
                
                String destFileName = IvyPatternHelper.substitute(destPattern, artifact, conf);
                
                Set dest = (Set)artifactsToCopy.get(artifact);
                if (dest == null) {
                    dest = new HashSet();
                    artifactsToCopy.put(artifact, dest);
                }
                String copyDest = new File(destFileName).getAbsolutePath();
                dest.add(copyDest);
                
                Set conflicts = (Set)conflictsMap.get(copyDest);
                Set conflictsConf = (Set)conflictsConfMap.get(copyDest);
                if (conflicts == null) {
                    conflicts = new HashSet();
                    conflictsMap.put(copyDest, conflicts);
                }
                if (conflictsConf == null) {
                    conflictsConf = new HashSet();
                    conflictsConfMap.put(copyDest, conflictsConf);
                }
                conflicts.add(artifact);
                conflictsConf.add(conf);
            }
        }
        
        // resolve conflicts if any
        for (Iterator iter = conflictsMap.keySet().iterator(); iter.hasNext();) {
            String copyDest = (String)iter.next();
            Set artifacts = (Set)conflictsMap.get(copyDest);
            Set conflictsConfs = (Set)conflictsConfMap.get(copyDest);
            if (artifacts.size() > 1) {
                List artifactsList = new ArrayList(artifacts);
                // conflicts battle is resolved by a sort using a conflict resolving policy comparator
                // which consider as greater a winning artifact
                Collections.sort(artifactsList, getConflictResolvingPolicy());
                // after the sort, the winning artifact is the greatest one, i.e. the last one
                Message.info("\tconflict on "+copyDest+" in "+conflictsConfs+": "+((Artifact)artifactsList.get(artifactsList.size() -1)).getModuleRevisionId().getRevision()+" won");
                
                // we now iterate over the list beginning with the artifact preceding the winner,
                // and going backward to the least artifact
                for (int i=artifactsList.size() - 2; i >=0; i--) {
                    Artifact looser = (Artifact)artifactsList.get(i);
                    Message.verbose("\t\tremoving conflict looser artifact: "+looser);
                    // for each loser, we remove the pair (loser - copyDest) in the artifactsToCopy map
                    Set dest = (Set)artifactsToCopy.get(looser);
                    dest.remove(copyDest);
                    if (dest.isEmpty()) {
                        artifactsToCopy.remove(looser);
                    }
                }
            }
        }
        return artifactsToCopy;
    }
    
    private boolean upToDate(File source, File target) {
        if (!target.exists()) {
            return false;
        }
        return source.lastModified() <= target.lastModified();
    }

    /**
     * The returned comparator should consider greater the artifact which
     * gains the conflict battle.
     * This is used only during retrieve... prefer resolve conflict manager
     * to resolve conflicts.
     * @return
     */
    private Comparator getConflictResolvingPolicy() {
        return new Comparator() {
            // younger conflict resolving policy
            public int compare(Object o1, Object o2) {
                Artifact a1 = (Artifact)o1;
                Artifact a2 = (Artifact)o2;
                if (a1.getPublicationDate().after(a2.getPublicationDate())) {
                    // a1 is after a2 <=> a1 is younger than a2 <=> a1 wins the conflict battle
                    return +1;
                } else if (a1.getPublicationDate().before(a2.getPublicationDate())) {
                    // a1 is before a2 <=> a2 is younger than a1 <=> a2 wins the conflict battle
                    return -1;
                } else {
                    return 0;
                }
            }
        };
    }


}