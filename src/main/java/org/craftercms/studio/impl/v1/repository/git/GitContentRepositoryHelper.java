/*
 * Crafter Studio
 *
 * Copyright (C) 2007-2016 Crafter Software Corporation.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.craftercms.studio.impl.v1.repository.git;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import com.google.gdata.util.common.base.StringUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.craftercms.studio.api.v1.constant.GitRepositories;
import org.craftercms.studio.api.v1.log.Logger;
import org.craftercms.studio.api.v1.log.LoggerFactory;
import org.craftercms.studio.api.v1.service.security.SecurityProvider;
import org.craftercms.studio.api.v1.util.StudioConfiguration;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FS;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.craftercms.studio.impl.v1.repository.git.GitContentRepositoryConstants.*;

/**
 * Created by Sumer Jabri
 */
public class GitContentRepositoryHelper {
    private static final Logger logger = LoggerFactory.getLogger(GitContentRepositoryHelper.class);

    Map<String, Repository> sandboxes = new HashMap<>();
    Map<String, Repository> published = new HashMap<>();

    Repository globalRepo = null; // TODO: SJ: TODAY: Must get this initialized in an init or bootstrap

    StudioConfiguration studioConfiguration;
    SecurityProvider securityProvider;

    GitContentRepositoryHelper(StudioConfiguration studioConfiguration, SecurityProvider securityProvider) {
        this.studioConfiguration = studioConfiguration;
        this.securityProvider = securityProvider;
    }

    public boolean buildGlobalRepo() throws IOException {
        boolean toReturn = false;
        Path siteRepoPath = buildRepoPath(GitRepositories.GLOBAL).resolve(GIT_ROOT);

        if (Files.exists(siteRepoPath)) {
            globalRepo = openRepository(siteRepoPath);
            toReturn = true;
        }

        return toReturn;
    }

    public boolean buildSiteRepo(String site) {
        boolean toReturn = false;
        Repository sandboxRepo;
        Repository publishedRepo;

        Path siteSandboxRepoPath = buildRepoPath(GitRepositories.SANDBOX, site).resolve(GIT_ROOT);
        Path sitePublishedRepoPath = buildRepoPath(GitRepositories.SANDBOX, site).resolve(GIT_ROOT);

        try {
            if (Files.exists(siteSandboxRepoPath)) {
                // Build and put in cache
                sandboxRepo = openRepository(siteSandboxRepoPath);
                sandboxes.put(site, sandboxRepo);
                toReturn = true;
            }
        } catch (IOException e) {
            logger.error("Failed to create sandbox repo for site: " + site + " using path " + siteSandboxRepoPath
                .toString(), e);
        }

        try {
            if (toReturn && Files.exists(sitePublishedRepoPath)) {
                // Build and put in cache
                publishedRepo = openRepository(sitePublishedRepoPath);
                sandboxes.put(site, publishedRepo);

                toReturn = true;
            }
        } catch (IOException e) {
            logger.error("Failed to create published repo for site: " + site + " using path " + sitePublishedRepoPath
                .toString(), e);
        }

        return toReturn;
    }

    public Repository openRepository(Path repositoryPath) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repository = builder
            .setGitDir(repositoryPath.toFile())
            .readEnvironment()
            .findGitDir()
            .build();
        return repository;
    }

    public RevTree getTree(Repository repository) throws AmbiguousObjectException, IncorrectObjectTypeException,
        IOException, MissingObjectException {
        ObjectId lastCommitId = repository.resolve(Constants.HEAD);


        // a RevWalk allows to walk over commits based on some filtering
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(lastCommitId);

            // and using commit's tree find the path
            RevTree tree = commit.getTree();
            return tree;
        }
    }

    public RevTree getTreeForCommit(Repository repository, String commitId) throws AmbiguousObjectException, IncorrectObjectTypeException,
        IOException, MissingObjectException {
        ObjectId commitObjectId = repository.resolve(commitId);


        // a RevWalk allows to walk over commits based on some filtering
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(commitObjectId);

            // and using commit's tree find the path
            RevTree tree = commit.getTree();
            return tree;
        }
    }

    public String getGitPath(String path) {
        Path gitPath = Paths.get(path);
        gitPath = gitPath.normalize();
        try {
            gitPath = Paths.get("/").relativize(gitPath);
        } catch (IllegalArgumentException e) {
            logger.debug("Path: " + path + " is already relative path.");
        }
        return gitPath.toString();
    }

    public AbstractTreeIterator prepareTreeParser(Repository repository, String ref) throws IOException,
        MissingObjectException,
        IncorrectObjectTypeException {
        // from the commit we can build the tree which allows us to construct the TreeParser
        Ref head = repository.exactRef(ref);
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(head.getObjectId());
            RevTree tree = walk.parseTree(commit.getTree().getId());

            CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
            try (ObjectReader oldReader = repository.newObjectReader()) {
                oldTreeParser.reset(oldReader, tree.getId());
            }

            walk.dispose();

            return oldTreeParser;
        }
    }

    public Repository createGitRepository(Path path) {
        Repository toReturn;

        try {
            // TODO: SJ: This needs to be refactored to the following:
            // TODO: SJ: If the site exists, return that information to the caller so they can decide what to do
            // TODO: SJ: and don't take any action. If the site doesn't exist, go ahead and create
            Files.deleteIfExists(path);
            path = Paths.get(path.toAbsolutePath().toString(), GIT_ROOT);
            toReturn = FileRepositoryBuilder.create(path.toFile());
            toReturn.create();
        } catch (IOException e) {
            logger.error("Error while creating repository for site with path" + path.toString(), e);
            toReturn = null;
        }

        return toReturn;
    }

    public Path buildRepoPath(GitRepositories repoType) {
        return buildRepoPath(repoType, StringUtil.EMPTY_STRING);
    }
    public Path buildRepoPath(GitRepositories repoType, String site) {
        Path path;
        switch (repoType) {
            case SANDBOX:
                path = Paths.get(studioConfiguration.getProperty(StudioConfiguration.REPO_BASE_PATH), studioConfiguration.getProperty(StudioConfiguration.SITES_REPOS_PATH), site, studioConfiguration.getProperty(StudioConfiguration.SANDBOX_PATH));
                break;
            case PUBLISHED:
                path = Paths.get(studioConfiguration.getProperty(StudioConfiguration.REPO_BASE_PATH), studioConfiguration.getProperty(StudioConfiguration.SITES_REPOS_PATH), site, studioConfiguration.getProperty(StudioConfiguration.PUBLISHED_PATH));
                break;
            case GLOBAL:
                path = Paths.get(studioConfiguration.getProperty(StudioConfiguration.REPO_BASE_PATH), studioConfiguration.getProperty(StudioConfiguration.GLOBAL_REPO_PATH));
                break;
            default:
                path = null;
        }

        return path;
    }

    /**
     * Create a site git repository from scratch
     * @param site
     * @return true if successful, false otherwise
     */
    public boolean createSiteGitRepo(String site) {
        boolean toReturn;
        Repository sandboxRepo = null;
        Repository publishedRepo = null;

        // Build a path for the site/sandbox
        Path siteSandboxPath = buildRepoPath(GitRepositories.SANDBOX, site);
        // Built a path for the site/published
        Path sitePublishedPath = buildRepoPath(GitRepositories.PUBLISHED, site);

        // Create Sandbox
        sandboxRepo = createGitRepository(siteSandboxPath);

        // Create Published
        if (sandboxRepo != null) {
            publishedRepo = createGitRepository(sitePublishedPath);
        }

        toReturn = (sandboxRepo != null) && (publishedRepo != null);

        if (toReturn) {
            sandboxes.put(site, sandboxRepo);
            published.put(site, publishedRepo);
        }

        return toReturn;
    }

    public boolean createGlobalRepo() {
        boolean toReturn = false;
        Path globalConfigRepoPath = buildRepoPath(GitRepositories.GLOBAL).resolve(GIT_ROOT);

        if (!Files.exists(globalConfigRepoPath)) {
            // Git repository doesn't exist for global, but the folder might be present, let's delete if exists
            Path globalConfigPath = globalConfigRepoPath.getParent();

            // Create the global repository folder
            try {
                Files.deleteIfExists(globalConfigPath);
                logger.info("Bootstrapping repository...");
                Files.createDirectories(globalConfigPath);
                globalRepo = createGitRepository(globalConfigPath);
                toReturn = true;
            } catch (IOException e) {
                // Something very wrong has happened
                logger.error("Bootstrapping repository failed", e);
            }
        } else {
            logger.error("Detected existing global repository, will not create new one.");
            toReturn = false;
        }

        return toReturn;
    }

    public boolean copyContentFromBlueprint(String blueprint, String site) {
        boolean toReturn = true;

        // Build a path to the Sandbox repo we'll be copying to
        Path siteRepoPath = buildRepoPath(GitRepositories.SANDBOX, site);
        // Build a path to the blueprint
        Path blueprintPath = buildRepoPath(GitRepositories.GLOBAL).resolve(Paths.get(studioConfiguration.getProperty
            (StudioConfiguration.BLUE_PRINTS_PATH), blueprint));
        EnumSet<FileVisitOption> opts = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
        // Let's copy!
        TreeCopier tc = new TreeCopier(blueprintPath, siteRepoPath);
        try {
            Files.walkFileTree(blueprintPath, opts, Integer.MAX_VALUE, tc);
        } catch (IOException err) {
            logger.error("Error copping files from blueprint", err);
            toReturn = false;
        }

        return toReturn;
    }

    public boolean bulkImport(String site /* , Map<String, String> filesCommitIds */) {
        // TODO: SJ: Define this further and build it along with API & Content Service equivalent with business logic
        // write all files to disk
        // commit all files
        // return data structure of file name & commit id per file
        // the caller will update the database
        //
        // considerations:
        //   accept a zip file
        //   accept a root folder or allow nesting
        //   content service should call this and then update the database
        //   find an efficient way to bulk write the files and then do a single commit across all
        return false;
    }

    /**
     * Perform an initial commit after large changes to a site. Will not work against the global config repo.
     * @param site
     * @param message
     * @return true if successful, false otherwise
     */
    public boolean performInitialCommit(String site, String message) {
        boolean toReturn = true;

        try {
            Repository repo = getRepository(site, GitRepositories.SANDBOX);

            Git git = new Git(repo);

            Status status = git.status().call();

            if (status.hasUncommittedChanges() || !status.isClean()) {
                DirCache dirCache = git.add().addFilepattern(GIT_COMMIT_ALL_ITEMS).call();
                RevCommit commit = git.commit()
                    .setMessage(message)
                    .call();
                // TODO: SJ: Do we need the commit id?
                // commitId = commit.getId().toString();
            }
        } catch (GitAPIException err) {
            logger.error("error creating initial commit for site:  " + site, err);
            toReturn = false;
        }

        return toReturn;
    }

    // SJ: Helper methods
    public Repository getRepository(String site, GitRepositories gitRepository) {
        Repository repo;

        logger.debug("getRepository invoked with site" + site + "Repository Type: " + gitRepository.toString());

        switch (gitRepository) {
            case SANDBOX:
                repo = sandboxes.get(site);
                if (repo == null) {
                    if (buildSiteRepo(site)) {
                        repo = sandboxes.get(site);
                    } else {
                        logger.error("error getting the repository for site: " + site);
                    }
                }
                break;
            case PUBLISHED:
                repo = published.get(site);
                if (repo == null) {
                    if (buildSiteRepo(site)) {
                        repo = published.get(site);
                    } else {
                        logger.error("error getting the repository for site: " + site);
                    }
                }
                break;
            case GLOBAL:
                repo = globalRepo;
                break;
            default:
                repo = null;
        }

        if (repo != null) {
            logger.debug("success in getting the repository for site: " + site);
        } else {
            logger.debug("failure in getting the repository for site: " + site);
        }

        return repo;
    }

    public boolean writeFile(Repository repo, String site, String path, InputStream content) {
        // Create folder structure, create file, and write the bits

        boolean result;
        try {
            String gitPath = getGitPath(path);
            RevTree tree = getTree(repo);
            TreeWalk tw = TreeWalk.forPath(repo, gitPath, tree);

            FS fs = FS.detect();
            File repoRoot = repo.getWorkTree();
            Path filePath;
            if (tw == null) {
                filePath = Paths.get(fs.normalize(repoRoot.getPath()), gitPath);
            } else {
                filePath = Paths.get(fs.normalize(repoRoot.getPath()), tw.getPathString());
            }

            File file = filePath.toFile();

            // Create parent folders
            File folder = file.getParentFile();
            if (folder != null) {
                if (!folder.exists()) {
                    folder.mkdirs();
                }
            }

            // Add the file to the index
            if (!Files.exists(filePath)) {
                filePath = Files.createFile(filePath);
            }

            // Write the bits
            FileUtils.writeByteArrayToFile(file, IOUtils.toByteArray(content));

            // Add the file to git
            // TODO: SJ: See if this is the fastest way to do this or if keeping a repo + git around would be faster
            Git git = new Git(repo);
            git.add()
                .addFilepattern(gitPath)
                .call();

            result = true;
        } catch (IOException | GitAPIException err) {
            logger.error("error writing file: site: " + site + " path: " + path, err);
            result = false;
        }

        return result;
    }

    public String commitFile(Repository repo, String site, String path) {
        String commitId = null;
        String comment = "Save file " + path;

        String gitPath = getGitPath(path);
        Git git = new Git(repo);

        Status status = null;
        try {
            status = git.status().addPath(gitPath).call();
        } catch (GitAPIException e) {
            logger.error("error adding file to git: site: " + site + " path: " + path, e);
        }

        // TODO: SJ: Below needs more thought and refactoring to detect issues with git repo and report them
        if (status.hasUncommittedChanges() || !status.isClean()) {
            RevCommit commit = null;
            try {
                commit = git.commit().setOnly(gitPath).setMessage(comment).call();
            } catch (GitAPIException e) {
                logger.error("error committing file to git: site: " + site + " path: " + path, e);
            }
            commitId = commit.getId().toString();
        }

        return commitId;
    }

    /**
     * Return the current user identity as a jgit PersonIdent
     *
     * @return current user as a PersonIdent
     */
    public PersonIdent getCurrentUserIdent() {
        String userName = securityProvider.getCurrentUser();
        Map<String, String> currentUserProfile = securityProvider.getUserProfile(userName);
        PersonIdent currentUserIdent = new PersonIdent
            (currentUserProfile.get("firstName") + " " + currentUserProfile.get("lastName"),
                currentUserProfile.get("email"));

        return currentUserIdent;
    }
}