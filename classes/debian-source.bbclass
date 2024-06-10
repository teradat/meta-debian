#
# debian-source.bbclass
#
# Parse Debian Sources.gz from apt repo
# to generate informations for DEBIAN_SRC_URI and PV.

DEBIAN_CODENAME ?= "${DISTRO_CODENAME}"
DEBIAN_SOURCE_ENABLED ?= "0"
DEBIAN_SRC_FORCE_REGEN ?= "0"
DEBIAN_SECURITY_UPDATE_ENABLED ?= "0"

def fetch_Sources_gz(debian_mirror, debian_security_update_enabled, d):
    """
    Download 'dists/<codename>/main/source/Sources.gz' from Debian mirror.
    This file contains information about list tar files, checksum,
    version, directory location on Debian apt repo and many others fields.
    """
    import os
    import urllib.request
    import re

    debian_codename = d.getVar('DEBIAN_CODENAME', True)

    # DEBIAN_MIRROR is like http://ftp.debian.org/debian/pool
    # but we don't want a path with 'pool'
    if debian_security_update_enabled:
        if '/debian-security/pool/updates' in debian_mirror:
            debian_mirror_nopool = debian_mirror.replace('/debian-security/pool/updates', '/debian-security')
        if '/extended-lts/pool' in debian_mirror:
            debian_mirror_nopool = debian_mirror.replace('/extended-lts/pool', '/extended-lts')
    else:
        debian_mirror_nopool = debian_mirror.replace('/debian/pool', '/debian')

    # Get checksum of Sources.gz from Release file
    bb.plain('Checking Debian Release ...')
    if debian_security_update_enabled:
        if 'debian-security' in debian_mirror_nopool:
            release_file_url = '%s/dists/%s/updates/Release' % (
                debian_mirror_nopool, debian_codename)
        if 'extended-lts' in debian_mirror_nopool:
            release_file_url = '%s/dists/%s-lts/Release' % (
                debian_mirror_nopool, debian_codename)
    else:
        release_file_url = '%s/dists/%s/Release' % (
            debian_mirror_nopool, debian_codename)

    release_file = urllib.request.urlopen(release_file_url).read()
    release_content = release_file.decode('utf-8').split('\n')
    md5sum_Source_gz = ''
    sources_gz_line = r'^ \S{32}\s*\S+\s*main/source/Sources.gz'
    for line in release_content:
        if re.match(sources_gz_line, line):
            md5sum_Source_gz = line.split()[0]

    dl_dir = d.getVar('DL_DIR', True)
    old_Sources_gz = os.path.join(dl_dir,'Sources.gz')
    if os.path.isfile(old_Sources_gz):
        old_md5sum_Source_gz = bb.utils.md5_file(old_Sources_gz)
        if md5sum_Source_gz == old_md5sum_Source_gz:
            bb.plain('Sources.gz is not changed.')
            force = d.getVar('DEBIAN_SRC_FORCE_REGEN', True)
            if force != "1":
                return False
            else:
                bb.plain('Force regenerate source code information.')
                return True
        else:
            os.remove(old_Sources_gz)

    # Get Sources.gz file
    if debian_security_update_enabled:
        if 'debian-security' in debian_mirror_nopool:
            sources_gz = '%s/dists/%s/updates/main/source/Sources.gz;md5sum=%s' % (
                debian_mirror_nopool, debian_codename, md5sum_Source_gz)
        if 'extended-lts' in debian_mirror_nopool:
            sources_gz = '%s/dists/%s-lts/main/source/Sources.gz;md5sum=%s' % (
                debian_mirror_nopool, debian_codename, md5sum_Source_gz)
    else:
        sources_gz = '%s/dists/%s/main/source/Sources.gz;md5sum=%s' % (
            debian_mirror_nopool, debian_codename, md5sum_Source_gz)

    try:
        bb.plain('Fetching Debian Sources.gz ...')
        fetcher = bb.fetch2.Fetch([sources_gz], d)
        fetcher.download()
    except bb.fetch2.BBFetchException as e:
        bb.warn("Failed to fetch Sources.gz. Continue building.")
        return False

    return True

def save_to_file(old_pkg_dpv_map, package, dpv, pv, repack_pv, directory, files, md5sum, sha256sum, debian_security_update_enabled, d):
    """
    When parsing Sources.gz, informations about DEBIAN_SRC_URI and PV will be save
    to meta-debian/recipes-debian/sources/<source name>.inc.

    It's hard to check which recipe that package belongs to
    because recipes haven't been parsed at the time this event is fired,
    so we don't have information about BPN.
    Then we don't know which packages should be generated DEBIAN_SRC_URI.

    Only save information to file if that file already exists (empty or not).
    We don't want to create thousands of .inc files.
    """
    if not package:
        return

    import os
    import debian.debian_support

    layer_collections = d.getVar('BBFILE_COLLECTIONS', True)
    for layer_name in layer_collections.split():
        layerdir_name = 'LAYERDIR_DEBIAN_' + layer_name
        layerdir = d.getVar(layerdir_name, True)
        if layerdir is None:
            continue
        filepath = '%s/recipes-debian/sources/%s.inc' % (layerdir, package)
        if not os.path.isfile(filepath):
           continue

        import re
        epoch = ''
        noepoch_dpv = re.sub(r'^\S*:', '', dpv)
        if dpv != noepoch_dpv:
            epoch = re.sub(r':\S*', '', dpv)

        # If package is not in old_pkg_dpv_map that means target package is adding this time.
        if package in old_pkg_dpv_map:
            if debian.debian_support.version_compare(old_pkg_dpv_map[package], noepoch_dpv) > 0:
                continue

        source_info = '# This is generated by debian-source.bbclass\n'
        source_info += 'DPV = "%s"\n' % noepoch_dpv
        source_info += 'DPV_EPOCH = "%s"\n' % epoch
        source_info += 'REPACK_PV = "%s"\n' % repack_pv
        source_info += 'PV = "%s"\n' % pv
        if debian_security_update_enabled:
            if "pool/updates/" in directory:
                debian_uri = re.sub("^pool/updates/", "${DEBIAN_SECURITY_UPDATE_MIRROR}/", directory)
            else:
                debian_uri = re.sub("^pool/", "${DEBIAN_ELTS_SECURITY_UPDATE_MIRROR}/", directory)
        else:
            debian_uri = re.sub("^pool/", "${DEBIAN_MIRROR}/", directory)
        src_uri = 'DEBIAN_SRC_URI = " \\\n'
        src_uri_md5 = ''
        src_uri_sha256 = ''
        for file in files:
            prevent_apply = ""
            if ".diff" in file or ".patch" in file:
                prevent_apply = ";apply=no"
            nametag = file.replace('~', '_')
            src_uri += '    %s/%s;name=%s%s \\\n' % (debian_uri, file, nametag, prevent_apply)
            src_uri_md5 += 'SRC_URI[%s.md5sum] = "%s"\n' % (nametag, md5sum[file])
            src_uri_sha256 += 'SRC_URI[%s.sha256sum] = "%s"\n' % (nametag, sha256sum[file])
        src_uri += '"\n'

        source_info += '\n' + src_uri + '\n' + src_uri_md5 + '\n' + src_uri_sha256

        f = open(filepath, 'w')
        f.write(source_info)
        f.close()

def parse_Sources_gz(old_pkg_dpv_map, debian_security_update_enabled, d):
    """
    Parse Sources.gz to get informations about source package's tarball.
    """
    import gzip
    import re

    dl_dir = d.getVar('DL_DIR', True)
    sources_gz = dl_dir + "/Sources.gz"

    with gzip.open(sources_gz, 'rt') as f:
        bb.plain('Parsing Debian Sources.gz ...')

        package = ''
        directory = ''
        files = []
        md5sum = {}
        sha256sum = {}
        full_version = ''
        upstream_version = ''
        repack_version = ''

        in_Files_section = False
        in_Sha256_section = False

        for line in f:
            line = line.rstrip()
            if line.startswith(' '):
                if in_Files_section:
                    splits = line.split(' ')
                    files.append(splits[3])
                    md5sum[splits[3]] = splits[1]
                elif in_Sha256_section:
                    splits = line.split(' ')
                    sha256sum[splits[3]] = splits[1]
            elif line.startswith('Files:'):
                in_Files_section = True
                in_Sha256_section = False
            elif line.startswith('Checksums-Sha256:'):
                in_Sha256_section = True
                in_Files_section = False
            else:
                in_Files_section = False
                in_Sha256_section = False
                if line.startswith('Package: '):
                    # Save information of the previous package
                    # before start parsing a new package.
                    save_to_file(old_pkg_dpv_map, package, full_version, upstream_version,
                        repack_version, directory, files, md5sum, sha256sum, debian_security_update_enabled, d)

                    package = line.replace('Package: ', '', 1)
                    files = []
                    md5sum = {}
                    sha256sum = {}
                elif line.startswith('Directory: '):
                    directory = line.replace('Directory: ', '', 1)
                elif line.startswith('Version: '):
                    full_version = line.replace('Version: ', '', 1)
                    noepoch_version = re.sub(r'^\S*:', '', full_version)
                    repack_version = re.sub(r'-[^-]*$', '', noepoch_version)
                    upstream_version = re.sub(r'[\+,\.](dfsg|ds|repack).*', '', repack_version)


def get_pkg_dpv_map(d):
    """
    Get all the DPV of recipes which has been generated.
    This function helps eventhandler check if source code version has been updated.
    """
    import os

    pkg_dpv_map = {}
    layer_collections = d.getVar('BBFILE_COLLECTIONS', True)
    for layer_name in layer_collections.split():
        layerdir_name = 'LAYERDIR_DEBIAN_' + layer_name
        layerdir = d.getVar(layerdir_name, True)
        if layerdir is None:
            continue

        sources_dir = os.path.join(layerdir, 'recipes-debian/sources')

        if not os.path.isdir(sources_dir):
            continue

        for f in os.listdir(sources_dir):
            filepath = os.path.join(sources_dir, f)
            if not (f.endswith('.inc') and os.path.isfile(filepath)):
                continue

            pkg = f.replace('.inc', '')
            with open(filepath, 'r') as inc_f:
                for line in inc_f:
                    if line.startswith('DPV ='):
                        dpv = line.split('=')[1].strip().replace('"', '')
                        pkg_dpv_map[pkg] = dpv
                        break

    return pkg_dpv_map

addhandler debian_source_eventhandler
debian_source_eventhandler[eventmask] = "bb.event.ParseStarted"
python debian_source_eventhandler() {

    update_targets = [
        {
            'debian_mirror': d.getVar('DEBIAN_MIRROR', True),
            'debian_security_update_enabled': False,
        }
    ]

    if d.getVar('DEBIAN_SECURITY_UPDATE_ENABLED', True) == '1':
        update_targets.insert(0, {
            'debian_mirror': d.getVar('DEBIAN_SECURITY_UPDATE_MIRROR', True),
            'debian_security_update_enabled': True,
        })
        bb.warn('debian security update repository is enabled')

        elts_update_mirror = d.getVar('DEBIAN_ELTS_SECURITY_UPDATE_MIRROR', True)
        if elts_update_mirror is not None and elts_update_mirror != "":
            update_targets.insert(0, {
                'debian_mirror': elts_update_mirror,
                'debian_security_update_enabled': True,
            })
            bb.warn('debian ELTS security update repository is enabled')

    debian_source_enabled = d.getVar('DEBIAN_SOURCE_ENABLED', True)
    if debian_source_enabled == '0':
        # Nothing to do
        return

    for update_target in update_targets:
        if not fetch_Sources_gz(update_target['debian_mirror'], update_target['debian_security_update_enabled'], d):
            continue

        old_pkg_dpv_map = get_pkg_dpv_map(d)
        parse_Sources_gz(old_pkg_dpv_map, update_target['debian_security_update_enabled'], d)
        new_pkg_dpv_map = get_pkg_dpv_map(d)

        # Show a warning if version has been changed
        for pkg in old_pkg_dpv_map.keys():
            if pkg not in new_pkg_dpv_map:
                continue
            if old_pkg_dpv_map[pkg] != new_pkg_dpv_map[pkg]:
                bb.warn('Source code for package "%s" has been updated from "%s" to "%s"'
                        % (pkg, old_pkg_dpv_map[pkg], new_pkg_dpv_map[pkg]))
}
