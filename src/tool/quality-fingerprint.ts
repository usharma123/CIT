/**
 * Quality Fingerprint - Merkle tree staleness detection
 *
 * Uses a Merkle tree of content-hashed source files to detect exactly
 * which files were added, deleted, or modified between runs.
 *
 * V2 replaces the flat mtime:size fingerprint (V1) with:
 * - Per-file SHA-256 content hashes (with mtime+size cache hints)
 * - Directory nodes built bottom-up from sorted child hashes
 * - O(n) tree diffing via fileIndex maps
 * - Specific "file X deleted, file Y modified" reports
 *
 * Backward compatible: loads V1 fingerprints gracefully, saves V2.
 */

import * as fs from "fs"
import * as path from "path"
import * as crypto from "crypto"

// ============================================================================
// Types
// ============================================================================

export interface MerkleLeaf {
  type: "file"
  relativePath: string
  contentHash: string
  size: number
  mtime: number // epoch ms
}

export interface MerkleNode {
  type: "directory"
  relativePath: string
  hash: string
  children: Record<string, MerkleLeaf | MerkleNode>
}

export interface MerkleTree {
  rootHash: string
  root: MerkleNode
  fileCount: number
  fileIndex: Record<string, string> // relativePath -> contentHash
}

export interface MerkleFileDiff {
  added: string[]
  deleted: string[]
  modified: string[]
  unchanged: number
}

export interface MerkleTreeDiff {
  testFiles: MerkleFileDiff
  mainFiles: MerkleFileDiff
}

// --- Fingerprint versions ---

export interface InputFingerprintV1 {
  version: 1
  timestamp: string

  git: {
    sha: string
    isDirty: boolean
    dirtyHash: string | null
  }

  configs: {
    [filename: string]: string
  }

  sources: {
    testFingerprint: string
    mainFingerprint: string
    testFileCount: number
    mainFileCount: number
  }
}

export interface InputFingerprintV2 {
  version: 2
  timestamp: string

  git: {
    sha: string
    isDirty: boolean
    dirtyHash: string | null
  }

  configs: {
    [filename: string]: string
  }

  sources: {
    testTree: MerkleTree | null
    mainTree: MerkleTree | null
  }
}

export type InputFingerprint = InputFingerprintV1 | InputFingerprintV2

export interface FingerprintComparisonResult {
  isStale: boolean
  reasons: string[]
  details: {
    gitChanged: boolean
    dirtyStateChanged: boolean
    configsChanged: string[]
    sourcesChanged: boolean
  }
  treeDiff: MerkleTreeDiff | null
}

// ============================================================================
// File Paths
// ============================================================================

export function getFingerprintPath(projectRoot: string): string {
  return path.join(projectRoot, ".bootstrap", "quality-fingerprint.json")
}

// ============================================================================
// Hash Utilities
// ============================================================================

function hashString(content: string): string {
  return crypto.createHash("sha256").update(content).digest("hex").substring(0, 16)
}

function hashFile(filePath: string): string | null {
  try {
    const content = fs.readFileSync(filePath, "utf-8")
    return hashString(content)
  } catch {
    return null
  }
}

function hashFileContent(filePath: string): string | null {
  try {
    const content = fs.readFileSync(filePath)
    return crypto.createHash("sha256").update(content).digest("hex").substring(0, 16)
  } catch {
    return null
  }
}

// ============================================================================
// Git Operations
// ============================================================================

async function getGitSha(cwd: string): Promise<string | null> {
  try {
    const proc = Bun.spawn(["git", "rev-parse", "HEAD"], {
      cwd,
      stdout: "pipe",
      stderr: "pipe",
    })
    const output = await new Response(proc.stdout).text()
    await proc.exited
    return output.trim() || null
  } catch {
    return null
  }
}

async function getGitDirtyState(cwd: string): Promise<{ isDirty: boolean; hash: string | null }> {
  try {
    const statusProc = Bun.spawn(["git", "status", "--porcelain"], {
      cwd,
      stdout: "pipe",
      stderr: "pipe",
    })
    const statusOutput = await new Response(statusProc.stdout).text()
    await statusProc.exited

    const isDirty = statusOutput.trim().length > 0

    if (!isDirty) {
      return { isDirty: false, hash: null }
    }

    const diffProc = Bun.spawn(["git", "diff", "HEAD"], {
      cwd,
      stdout: "pipe",
      stderr: "pipe",
    })
    const diffOutput = await new Response(diffProc.stdout).text()
    await diffProc.exited

    const combinedOutput = statusOutput + "\n" + diffOutput
    const hash = hashString(combinedOutput)

    return { isDirty: true, hash }
  } catch {
    return { isDirty: false, hash: null }
  }
}

// ============================================================================
// Merkle Tree Construction
// ============================================================================

/**
 * Build a Merkle tree from files matching `pattern` under `rootDir`.
 *
 * Content-hashes each file (SHA-256, truncated to 16 hex chars).
 * Uses mtime+size as a cache hint: if `previousTree` has the same
 * mtime+size for a path, the stored hash is reused without re-reading.
 *
 * Directory nodes are built bottom-up by sorting child names and
 * concatenating their hashes.
 */
export function buildMerkleTree(
  rootDir: string,
  pattern: RegExp,
  previousTree?: MerkleTree | null,
  excludeDirs: string[] = ["target", "build", "node_modules", ".git"],
): MerkleTree | null {
  if (!fs.existsSync(rootDir)) {
    return null
  }

  const fileIndex: Record<string, string> = {}
  const previousIndex = previousTree?.fileIndex ?? {}

  // Collect all matching files with their metadata
  interface FileEntry {
    relativePath: string
    fullPath: string
    size: number
    mtime: number
  }
  const files: FileEntry[] = []

  const scanDir = (currentDir: string): void => {
    try {
      const entries = fs.readdirSync(currentDir, { withFileTypes: true })
      for (const entry of entries) {
        if (entry.isDirectory()) {
          if (!excludeDirs.includes(entry.name) && !entry.name.startsWith(".")) {
            scanDir(path.join(currentDir, entry.name))
          }
        } else if (entry.isFile() && pattern.test(entry.name)) {
          const fullPath = path.join(currentDir, entry.name)
          try {
            const stat = fs.statSync(fullPath)
            files.push({
              relativePath: path.relative(rootDir, fullPath),
              fullPath,
              size: stat.size,
              mtime: stat.mtime.getTime(),
            })
          } catch {
            // skip unreadable files
          }
        }
      }
    } catch {
      // Ignore errors (permission issues, etc.)
    }
  }

  scanDir(rootDir)

  if (files.length === 0) {
    return null
  }

  // Hash each file, reusing cached hashes when mtime+size match
  const leaves: MerkleLeaf[] = []

  for (const file of files) {
    let contentHash: string | null = null

    // Check cache: if previous tree has this path with same mtime+size, reuse hash
    if (previousTree) {
      const prevHash = previousIndex[file.relativePath]
      if (prevHash) {
        // Walk the previous tree to find the leaf and check mtime+size
        const prevLeaf = findLeafInTree(previousTree.root, file.relativePath)
        if (prevLeaf && prevLeaf.mtime === file.mtime && prevLeaf.size === file.size) {
          contentHash = prevHash
        }
      }
    }

    if (!contentHash) {
      contentHash = hashFileContent(file.fullPath)
    }

    if (contentHash) {
      leaves.push({
        type: "file",
        relativePath: file.relativePath,
        contentHash,
        size: file.size,
        mtime: file.mtime,
      })
      fileIndex[file.relativePath] = contentHash
    }
  }

  // Build directory tree bottom-up
  const root = buildDirectoryTree(leaves, "")

  return {
    rootHash: root.hash,
    root,
    fileCount: leaves.length,
    fileIndex,
  }
}

function findLeafInTree(node: MerkleNode, relativePath: string): MerkleLeaf | null {
  const parts = relativePath.split(path.sep)

  let current: MerkleNode | MerkleLeaf = node
  for (const part of parts) {
    if (current.type !== "directory") return null
    const child = current.children[part]
    if (!child) return null
    current = child
  }

  return current.type === "file" ? current : null
}

function buildDirectoryTree(leaves: MerkleLeaf[], dirRelativePath: string): MerkleNode {
  const children: Record<string, MerkleLeaf | MerkleNode> = {}

  // Group leaves by their first path segment relative to this directory
  const groups = new Map<string, MerkleLeaf[]>()

  for (const leaf of leaves) {
    const relativeToDir = dirRelativePath
      ? leaf.relativePath.substring(dirRelativePath.length + 1)
      : leaf.relativePath
    const segments = relativeToDir.split(path.sep)

    if (segments.length === 1) {
      // Direct child file
      children[segments[0]] = leaf
    } else {
      // Belongs in a subdirectory
      const subDirName = segments[0]
      if (!groups.has(subDirName)) {
        groups.set(subDirName, [])
      }
      groups.get(subDirName)!.push(leaf)
    }
  }

  // Recursively build subdirectory nodes
  for (const [subDirName, subLeaves] of groups) {
    const subDirPath = dirRelativePath ? `${dirRelativePath}${path.sep}${subDirName}` : subDirName
    children[subDirName] = buildDirectoryTree(subLeaves, subDirPath)
  }

  // Compute directory hash from sorted children
  const sortedNames = Object.keys(children).sort()
  const hashInput = sortedNames
    .map((name) => {
      const child = children[name]
      return child.type === "file" ? `${name}:${child.contentHash}` : `${name}:${child.hash}`
    })
    .join("\n")

  return {
    type: "directory",
    relativePath: dirRelativePath,
    hash: hashString(hashInput),
    children,
  }
}

// ============================================================================
// Merkle Tree Diffing
// ============================================================================

/**
 * O(n) comparison of two Merkle trees via their fileIndex maps.
 * Returns lists of added, deleted, modified files and count of unchanged.
 */
export function diffMerkleTrees(
  current: MerkleTree | null,
  stored: MerkleTree | null,
): MerkleFileDiff {
  const added: string[] = []
  const deleted: string[] = []
  const modified: string[] = []
  let unchanged = 0

  const currentIndex = current?.fileIndex ?? {}
  const storedIndex = stored?.fileIndex ?? {}

  // Check all files in current tree
  for (const [filePath, hash] of Object.entries(currentIndex)) {
    const storedHash = storedIndex[filePath]
    if (storedHash === undefined) {
      added.push(filePath)
    } else if (storedHash !== hash) {
      modified.push(filePath)
    } else {
      unchanged++
    }
  }

  // Check for files only in stored tree (deleted)
  for (const filePath of Object.keys(storedIndex)) {
    if (!(filePath in currentIndex)) {
      deleted.push(filePath)
    }
  }

  return { added, deleted, modified, unchanged }
}

// ============================================================================
// Fingerprint Operations
// ============================================================================

/**
 * Compute the current input fingerprint for a project.
 * When `previousFingerprint` (v2) is provided, reuses cached hashes
 * for files whose mtime+size haven't changed.
 */
export async function computeFingerprint(
  projectPath: string,
  testSourceDir: string | null,
  mainSourceDir: string | null,
  previousFingerprint?: InputFingerprintV2 | null,
): Promise<InputFingerprintV2> {
  // Git state
  const gitSha = (await getGitSha(projectPath)) || "unknown"
  const dirtyState = await getGitDirtyState(projectPath)

  // Config file hashes
  const configFiles = ["pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"]
  const configs: { [key: string]: string } = {}

  for (const configFile of configFiles) {
    const configPath = path.join(projectPath, configFile)
    const hash = hashFile(configPath)
    if (hash) {
      configs[configFile] = hash
    }
  }

  // Build Merkle trees
  const javaPattern = /\.java$|\.kt$/
  const prevTestTree = previousFingerprint?.sources.testTree ?? null
  const prevMainTree = previousFingerprint?.sources.mainTree ?? null

  const testTree = testSourceDir
    ? buildMerkleTree(testSourceDir, javaPattern, prevTestTree)
    : null

  const mainTree = mainSourceDir
    ? buildMerkleTree(mainSourceDir, javaPattern, prevMainTree)
    : null

  return {
    version: 2,
    timestamp: new Date().toISOString(),
    git: {
      sha: gitSha,
      isDirty: dirtyState.isDirty,
      dirtyHash: dirtyState.hash,
    },
    configs,
    sources: {
      testTree,
      mainTree,
    },
  }
}

/**
 * Load a stored fingerprint from disk.
 * Handles both V1 and V2 formats.
 */
export function loadFingerprint(projectPath: string): InputFingerprint | null {
  const fingerprintPath = getFingerprintPath(projectPath)

  if (!fs.existsSync(fingerprintPath)) {
    return null
  }

  try {
    const data = JSON.parse(fs.readFileSync(fingerprintPath, "utf-8"))
    if (data.version === 1 || data.version === 2) {
      return data as InputFingerprint
    }
    return null
  } catch {
    return null
  }
}

/**
 * Save a fingerprint to disk.
 */
export function saveFingerprint(projectPath: string, fingerprint: InputFingerprint): void {
  const fingerprintPath = getFingerprintPath(projectPath)
  const dir = path.dirname(fingerprintPath)

  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true })
  }

  fs.writeFileSync(fingerprintPath, JSON.stringify(fingerprint, null, 2), "utf-8")
}

/**
 * Compare two fingerprints to determine if artifacts are stale.
 *
 * When both are V2, produces a `treeDiff` with per-file detail.
 * When either is V1, falls back to flat hash comparison.
 */
export function compareFingerprints(
  current: InputFingerprint,
  stored: InputFingerprint,
): FingerprintComparisonResult {
  const reasons: string[] = []
  const details = {
    gitChanged: false,
    dirtyStateChanged: false,
    configsChanged: [] as string[],
    sourcesChanged: false,
  }

  // Check git SHA
  if (current.git.sha !== stored.git.sha) {
    details.gitChanged = true
    reasons.push(`Git commit changed: ${stored.git.sha.substring(0, 8)} → ${current.git.sha.substring(0, 8)}`)
  }

  // Check dirty state
  if (current.git.isDirty !== stored.git.isDirty) {
    details.dirtyStateChanged = true
    reasons.push(
      current.git.isDirty ? "Working directory now has uncommitted changes" : "Working directory is now clean",
    )
  } else if (current.git.isDirty && current.git.dirtyHash !== stored.git.dirtyHash) {
    details.dirtyStateChanged = true
    reasons.push("Uncommitted changes have been modified")
  }

  // Check config files
  const allConfigFiles = new Set([...Object.keys(current.configs), ...Object.keys(stored.configs)])
  for (const configFile of allConfigFiles) {
    const currentHash = current.configs[configFile]
    const storedHash = stored.configs[configFile]

    if (currentHash !== storedHash) {
      details.configsChanged.push(configFile)
      if (!storedHash) {
        reasons.push(`Config file added: ${configFile}`)
      } else if (!currentHash) {
        reasons.push(`Config file removed: ${configFile}`)
      } else {
        reasons.push(`Config file changed: ${configFile}`)
      }
    }
  }

  // Check sources — V2 vs V2 uses tree diffing, otherwise flat comparison
  let treeDiff: MerkleTreeDiff | null = null

  if (current.version === 2 && stored.version === 2) {
    const testDiff = diffMerkleTrees(current.sources.testTree, stored.sources.testTree)
    const mainDiff = diffMerkleTrees(current.sources.mainTree, stored.sources.mainTree)

    treeDiff = { testFiles: testDiff, mainFiles: mainDiff }

    const testChanged = testDiff.added.length > 0 || testDiff.deleted.length > 0 || testDiff.modified.length > 0
    const mainChanged = mainDiff.added.length > 0 || mainDiff.deleted.length > 0 || mainDiff.modified.length > 0

    if (testChanged || mainChanged) {
      details.sourcesChanged = true

      if (testDiff.deleted.length > 0) {
        const fileNames = testDiff.deleted.map((f) => path.basename(f, path.extname(f)))
        reasons.push(`Test sources: ${testDiff.deleted.length} file(s) deleted (${fileNames.join(", ")})`)
      }
      if (testDiff.added.length > 0) {
        const fileNames = testDiff.added.map((f) => path.basename(f, path.extname(f)))
        reasons.push(`Test sources: ${testDiff.added.length} file(s) added (${fileNames.join(", ")})`)
      }
      if (testDiff.modified.length > 0) {
        const fileNames = testDiff.modified.map((f) => path.basename(f, path.extname(f)))
        reasons.push(`Test sources: ${testDiff.modified.length} file(s) modified (${fileNames.join(", ")})`)
      }

      if (mainDiff.deleted.length > 0) {
        const fileNames = mainDiff.deleted.map((f) => path.basename(f, path.extname(f)))
        reasons.push(`Main sources: ${mainDiff.deleted.length} file(s) deleted (${fileNames.join(", ")})`)
      }
      if (mainDiff.added.length > 0) {
        const fileNames = mainDiff.added.map((f) => path.basename(f, path.extname(f)))
        reasons.push(`Main sources: ${mainDiff.added.length} file(s) added (${fileNames.join(", ")})`)
      }
      if (mainDiff.modified.length > 0) {
        const fileNames = mainDiff.modified.map((f) => path.basename(f, path.extname(f)))
        reasons.push(`Main sources: ${mainDiff.modified.length} file(s) modified (${fileNames.join(", ")})`)
      }
    }
  } else {
    // V1 fallback — at least one side is V1
    const currentSourceHash = getSourceHash(current)
    const storedSourceHash = getSourceHash(stored)

    if (currentSourceHash.test !== storedSourceHash.test || currentSourceHash.main !== storedSourceHash.main) {
      details.sourcesChanged = true

      if (currentSourceHash.test !== storedSourceHash.test) {
        reasons.push("Test sources changed (flat hash comparison)")
      }
      if (currentSourceHash.main !== storedSourceHash.main) {
        reasons.push("Main sources changed (flat hash comparison)")
      }
    }
  }

  const isStale =
    details.gitChanged ||
    details.dirtyStateChanged ||
    details.configsChanged.length > 0 ||
    details.sourcesChanged

  return { isStale, reasons, details, treeDiff }
}

/**
 * Extract comparable source hashes regardless of fingerprint version.
 * Used for V1 fallback comparison.
 */
function getSourceHash(fp: InputFingerprint): { test: string; main: string } {
  if (fp.version === 1) {
    return { test: fp.sources.testFingerprint, main: fp.sources.mainFingerprint }
  }
  // V2: use rootHash from trees
  return {
    test: fp.sources.testTree?.rootHash ?? "empty",
    main: fp.sources.mainTree?.rootHash ?? "empty",
  }
}

/**
 * High-level function to check if artifacts are stale using fingerprints.
 * Returns null if fingerprint-based detection is not available (no stored fingerprint).
 */
export async function checkFingerprintStaleness(
  projectPath: string,
  testSourceDir: string | null,
  mainSourceDir: string | null,
  previousFingerprint?: InputFingerprint | null,
): Promise<FingerprintComparisonResult | null> {
  const stored = previousFingerprint !== undefined ? previousFingerprint : loadFingerprint(projectPath)

  if (!stored) {
    return null
  }

  const prevV2 = stored.version === 2 ? stored : null
  const current = await computeFingerprint(projectPath, testSourceDir, mainSourceDir, prevV2)
  return compareFingerprints(current, stored)
}
