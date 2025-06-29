#!/usr/bin/env python3

import argparse
import asyncio
import logging
import os
import tempfile
from pathlib import Path
from typing import Optional
from urllib.parse import quote

PACKAGES = {
    8: [
        "criu",
        "runc",
        "cri-o",
        "cri-tools",
        "skopeo",
        "openshift-clients",
        "openshift-hyperkube",
        "slirp4netns",
        "conmon-rs",
    ],
    9: [
        "criu",
        "runc",
        "cri-o",
        "cri-tools",
        "skopeo",
        "openshift-clients",
        "openshift-hyperkube",
        "openshift-kubelet",
        "slirp4netns",
        "conmon-rs",
        "openvswitch3.3",
        "openvswitch3.4",
        "openvswitch3.5",
        "openvswitch-selinux-extra-policy",
    ],
}

YUM_CONF_TEMPLATES = {
    8: """[main]
cachedir={CACHE_DIR}
keepcache=0
debuglevel=2
exactarch=1
obsoletes=1
gpgcheck=1
plugins=1
installonly_limit=3
reposdir=
skip_missing_names_on_install=0

[rhel-server-8-baseos]
name = rhel-server-8-baseos
baseurl = https://rhsm-pulp.corp.redhat.com/content/dist/rhel8/8/{ARCH}/baseos/os
enabled = 1
gpgcheck = 0

[rhel-server-8-appstream]
name = rhel-server-8-appstream
baseurl = https://rhsm-pulp.corp.redhat.com/content/dist/rhel8/8/{ARCH}/appstream/os/
enabled = 1
gpgcheck = 0

[rhel-server-8-fast-datapath]
name = rhel-server-8-fast-datapath
baseurl = https://rhsm-pulp.corp.redhat.com/content/dist/layered/rhel8/{ARCH}/fast-datapath/os/
enabled = 1
gpgcheck = 0

[rhel-server-8-ose-{OCP_VERSION}-rpms]
name = rhel-server-8-ose-{OCP_VERSION}-rpms
baseurl = https://ocp-artifacts.engineering.redhat.com/pub/RHOCP/plashets/{OCP_VERSION}/stream/el8/latest/{ARCH}/os
enabled = 1
gpgcheck = 0
module_hotfixes=1
""",
    9: """[main]
cachedir={CACHE_DIR}
keepcache=0
debuglevel=2
exactarch=1
obsoletes=1
gpgcheck=1
plugins=1
installonly_limit=3
reposdir=
skip_missing_names_on_install=0

[rhel-server-{EL}-baseos]
name = rhel-server-{EL}-baseos
baseurl = https://rhsm-pulp.corp.redhat.com/content/dist/rhel{EL}/{EL}/{ARCH}/baseos/os
enabled = 1
gpgcheck = 0
exclude = skopeo cri-o cri-tools conmon-rs runc

[rhel-server-{EL}-appstream]
name = rhel-server-{EL}-appstream
baseurl = https://rhsm-pulp.corp.redhat.com/content/dist/rhel{EL}/{EL}/{ARCH}/appstream/os/
enabled = 1
gpgcheck = 0
exclude = skopeo cri-o cri-tools conmon-rs runc

[rhel-server-{EL}-fast-datapath]
name = rhel-server-{EL}-fast-datapath
baseurl = https://rhsm-pulp.corp.redhat.com/content/dist/layered/rhel{EL}/{ARCH}/fast-datapath/os/
enabled = 1
gpgcheck = 0
exclude = skopeo cri-o cri-tools conmon-rs runc

[rhel-server-{EL}-ose-{OCP_VERSION}-rpms]
name = rhel-server-{EL}-ose-{OCP_VERSION}-rpms
baseurl = https://ocp-artifacts.engineering.redhat.com/pub/RHOCP/plashets/{OCP_VERSION}/stream/el{EL}/latest/{ARCH}/os
enabled = 1
gpgcheck = 0
module_hotfixes=1
""",
}

LOGGER = logging.getLogger(__name__)


async def download_rpms(ocp_version: str, arch: str, rhel_major: int, output_dir: os.PathLike):
    yum_conf_tmpl = YUM_CONF_TEMPLATES.get(rhel_major)
    if not yum_conf_tmpl:
        raise ValueError(f"Unsupported RHEL version '{rhel_major}'")

    with tempfile.TemporaryDirectory(prefix="collect_deps-working-", dir=os.curdir) as working_dir:
        working_dir = Path(working_dir).absolute()
        install_root_dir = working_dir / "install-root"
        yum_conf_filename = working_dir / "yum.conf"
        cache_dir = working_dir / "cache"

        yum_conf = yum_conf_tmpl.format(yum_conf_tmpl, OCP_VERSION=quote(
            ocp_version), ARCH=arch, CACHE_DIR=cache_dir, EL=rhel_major).strip()
        with open(yum_conf_filename, "w") as f:
            f.write(yum_conf)
        packages = PACKAGES[rhel_major]
        if arch == 'x86_64':
            packages.append('openshift-clients-redistributable')
        cmd = [
            "dnf",
            "download",
            f"--releasever={rhel_major}",
            "-c", f"{yum_conf_filename}",
            "--disableplugin=subscription-manager",
            "--downloadonly",
            #f"--installroot={Path(install_root_dir).absolute()}",
            f"--destdir={output_dir}",
            f"--forcearch={arch}",
        ]
        if rhel_major == 8:
            cmd.extend(['--resolve', '--alldeps'])

        cmd.append('--')
        cmd.extend(packages)

        LOGGER.info("Running command %s", cmd)
        env = os.environ.copy()
        # yum doesn't honor cachedir in the yum.conf. It keeps a user specific cache
        # https://unix.stackexchange.com/questions/92257/yum-user-temp-files-var-tmp-yum-fills-up-with-repo-data
        # override the location using TMPDIR
        tmp_dir = working_dir / "tmp"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        env['TMPDIR'] = str(tmp_dir)
        process = await asyncio.subprocess.create_subprocess_exec(*cmd, env=env)
        rc = await process.wait()
        if rc != 0:
            raise ChildProcessError(f"Process {cmd} exited with status {rc}")


async def create_repo(directory: str):
    cmd = ["createrepo_c", "-v", "--", f"{directory}"]
    LOGGER.info("Running command %s", cmd)
    process = await asyncio.subprocess.create_subprocess_exec(*cmd, env=os.environ.copy())
    rc = await process.wait()
    if rc != 0:
        raise ChildProcessError(f"Process {cmd} exited with status {rc}")


async def collect(ocp_version: str, arch: str, rhel_major: int, base_dir: Optional[str]):
    version_suffix = f"-el{rhel_major}" if rhel_major != 7 else ""
    base_dir = Path(base_dir or ".")
    output_dir = Path(base_dir, f"{ocp_version}{version_suffix}-beta")
    LOGGER.info(
        f"Downloading rpms to {output_dir} for OCP {ocp_version} - RHEL {rhel_major}...")
    await download_rpms(ocp_version, arch, rhel_major, output_dir)
    LOGGER.info(f"Creating repo {output_dir} for {arch} OCP {ocp_version} - RHEL {rhel_major}...")
    await create_repo(output_dir)


async def main():
    logging.basicConfig(level=logging.INFO)
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-dir", required=False,
                        help="Write repos to specified directory")
    parser.add_argument("--arch", required=False,
                        default='x86_64',
                        help="Write repos to specified directory")
    parser.add_argument("--el", required=False,
                        default='8',
                        help="The RHEL version from which to publish RPMs")
    parser.add_argument("ocp_version", help="OCP version. e.g. 4.11")
    args = parser.parse_args()

    tasks = list()
    tasks.append(collect(args.ocp_version,
                         args.arch,
                         int(args.el), base_dir=args.base_dir))
    await asyncio.gather(*tasks)


if __name__ == "__main__":
    asyncio.get_event_loop().run_until_complete(main())
