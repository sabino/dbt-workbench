from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import tempfile
import unittest

import yaml

from dbt_workbench.workspace import FileConflict, WorkspaceManager


class WorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.project = self.root / "user-project"
        (self.project / "models").mkdir(parents=True)
        (self.project / "dbt_project.yml").write_text(
            "name: sample\nprofile: sample\nmodel-paths: [models]\n"
        )
        (self.project / "profiles.yml").write_text(
            "sample:\n  outputs:\n    dev:\n      type: duckdb\n      password: do-not-persist-me\n"
        )
        (self.project / "models" / "orders.sql").write_text("select 1 as id\n")
        self.manager = WorkspaceManager(self.root / "application-data")
        self.opened = self.manager.open_project(str(self.project))
        self.project_id = self.opened["id"]

    def test_registry_survives_restart_and_open_is_idempotent(self):
        restarted = WorkspaceManager(self.manager.data_dir)
        self.assertEqual(restarted.get_project(self.project_id), self.opened)
        self.assertEqual(restarted.open_project(str(self.project)), self.opened)
        self.assertEqual(len(restarted.list_projects()), 1)
        self.assertEqual(self.opened["profiles_dir"], str(self.project))
        self.assertNotIn("do-not-persist-me", (self.manager.data_dir / "projects.json").read_text())

    def test_concurrent_registry_updates_are_not_lost(self):
        projects = []
        for number in range(4):
            project = self.root / f"additional-{number}"
            project.mkdir()
            (project / "dbt_project.yml").write_text(f"name: additional_{number}\n")
            projects.append(project)
        with ThreadPoolExecutor(max_workers=4) as executor:
            results = list(executor.map(
                lambda project: WorkspaceManager(self.manager.data_dir).open_project(str(project)), projects
            ))
        self.assertEqual(len({project["id"] for project in results}), 4)
        self.assertEqual(len(self.manager.list_projects()), 5)

    def test_demo_is_pinned_and_does_not_touch_the_original(self):
        original = (self.project / "models/orders.sql").read_bytes()
        demo = self.manager.create_demo()
        self.assertEqual(self.manager.create_demo(), demo)
        workspace = Path(demo["path"])
        provenance = json.loads((workspace / "PROVENANCE.json").read_text())
        self.assertEqual(provenance["commit"], "fd7bfacae4f497ff044a6a0275268676bf1b64c3")
        self.assertIn("Apache License", (workspace / "LICENSE").read_text())
        self.assertEqual(len(list((workspace / "seeds").glob("*.csv"))), 3)
        profiles = yaml.safe_load((Path(demo["profiles_dir"]) / "profiles.yml").read_text())
        outputs = profiles["jaffle_shop"]["outputs"]
        self.assertTrue(outputs["dev"]["attach"][0]["read_only"])
        self.assertEqual(outputs["dev"]["attach"][0]["path"], outputs["baseline"]["path"])
        self.assertTrue(Path(outputs["baseline"]["path"]).is_relative_to(self.manager.project_data(demo["id"])))
        self.assertEqual(self.manager.targets(demo["id"]), ["baseline", "dev"])
        self.assertEqual((self.project / "models/orders.sql").read_bytes(), original)

    def test_paths_cannot_escape_and_profiles_are_private(self):
        outside = self.root / "outside.sql"
        outside.write_text("private")
        paths = [
            "../outside.sql", str(outside), "models/../../outside.sql", "C:\\outside.sql",
            "models\\orders.sql", "profiles.yml", "target/model.sql", "models/invalid.txt",
        ]
        for path in paths:
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.manager.save_file(self.project_id, path, "modified", None)
        self.assertEqual(outside.read_text(), "private")
        self.assertNotIn("profiles.yml", {item["path"] for item in self.manager.list_files(self.project_id)})
        with self.assertRaises(ValueError):
            self.manager.read_file(self.project_id, "profiles.yml")

    def test_symlinks_are_neither_listed_read_nor_written(self):
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "private.sql").write_text("private")
        (self.project / "models" / "link.sql").symlink_to(outside / "private.sql")
        (self.project / "models" / "linked").symlink_to(outside, target_is_directory=True)
        for path in ["models/link.sql", "models/linked/private.sql"]:
            with self.subTest(path=path):
                with self.assertRaises(ValueError):
                    self.manager.read_file(self.project_id, path)
                with self.assertRaises(ValueError):
                    self.manager.save_file(self.project_id, path, "modified", None)
        listed = {item["path"] for item in self.manager.list_files(self.project_id)}
        self.assertNotIn("models/link.sql", listed)
        self.assertNotIn("models/linked/private.sql", listed)
        self.assertEqual((outside / "private.sql").read_text(), "private")

    def test_optimistic_writes_across_managers_conflict(self):
        before = self.manager.read_file(self.project_id, "models/orders.sql")
        def save(number):
            manager = WorkspaceManager(self.manager.data_dir)
            try:
                return manager.save_file(self.project_id, "models/orders.sql", f"select {number}", before["revision"])
            except FileConflict:
                return None
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(save, [2, 3]))
        self.assertEqual(sum(result is not None for result in results), 1)
        after = self.manager.read_file(self.project_id, "models/orders.sql")
        self.assertNotEqual(after["revision"], before["revision"])
        self.assertIn(after["content"], ["select 2", "select 3"])
        self.assertEqual(list((self.project / "models").glob(".orders.sql.*")), [])

    def test_targets_return_only_names_and_explicit_profiles_are_used(self):
        profiles = self.root / "custom-profiles"
        profiles.mkdir()
        (profiles / "profiles.yml").write_text(
            "sample:\n  outputs:\n    prod:\n      type: duckdb\n      password: secret\n    '../bad': {}\n"
        )
        opened = self.manager.open_project(str(self.project), str(profiles))
        self.assertEqual(self.manager.targets(opened["id"]), ["prod"])
        self.assertEqual(self.manager.dbt_args(opened["id"]), [
            "--project-dir", str(self.project), "--profiles-dir", str(profiles)
        ])

    def test_model_discovery_tracks_source_files_and_preserves_package_models(self):
        initial = self.manager.model_list(self.project_id)
        self.assertEqual([item["unique_id"] for item in initial], ["model.sample.orders"])
        manifest = self.root / "manifest.json"
        manifest.write_text(json.dumps({"nodes": {
            "model.sample.orders": {
                "resource_type": "model", "name": "orders", "original_file_path": "models/orders.sql",
                "config": {"materialized": "table"}, "depends_on": {"nodes": ["source.sample.raw.orders"]},
                "columns": {"id": {"name": "id"}},
            },
            "model.package.orders": {
                "resource_type": "model", "name": "orders", "original_file_path": "models/orders.sql",
                "config": {"materialized": "view"},
            },
        }}))
        self.manager.register_manifest(self.project_id, manifest)
        (self.project / "models/new_model.sql").write_text("select 2")
        models = {item["unique_id"]: item for item in self.manager.model_list(self.project_id)}
        self.assertEqual(set(models), {"model.sample.orders", "model.package.orders", "model.sample.new_model"})
        self.assertEqual(models["model.sample.orders"]["materialized"], "table")
        (self.project / "models/orders.sql").unlink()
        self.assertEqual({item["unique_id"] for item in self.manager.model_list(self.project_id)}, {
            "model.package.orders", "model.sample.new_model"
        })

    def test_generated_directories_are_not_editable_or_discovered(self):
        with (self.project / "dbt_project.yml").open("a") as handle:
            handle.write("target-path: generated\npackages-install-path: packages\n")
        for directory in ["target", "generated", "packages", ".git", ".venv"]:
            (self.project / directory).mkdir()
            (self.project / directory / "skip.sql").write_text("skip")
        files = self.manager.list_files(self.project_id)
        self.assertEqual({item["path"] for item in files}, {"dbt_project.yml", "models/orders.sql"})
        with self.assertRaises(ValueError):
            self.manager.read_file(self.project_id, "generated/skip.sql")

    def test_invalid_manifest_keeps_last_good_copy(self):
        source = self.root / "manifest.json"
        good = {"nodes": {}, "metadata": {"dbt_version": "1.12.5"}}
        source.write_text(json.dumps(good))
        self.manager.register_manifest(self.project_id, source)
        source.write_text("invalid")
        with self.assertRaises(ValueError):
            self.manager.register_manifest(self.project_id, source)
        self.assertEqual(self.manager.manifest(self.project_id), good)
        for malformed in ({"nodes": {"bad": None}}, {"nodes": {}, "metadata": []}):
            source.write_text(json.dumps(malformed))
            with self.subTest(malformed=malformed), self.assertRaises(ValueError):
                self.manager.register_manifest(self.project_id, source)
            self.assertEqual(self.manager.manifest(self.project_id), good)

    def test_baselines_are_copies_with_history_and_explicit_live_data_semantics(self):
        artifacts = self.root / "artifacts"
        artifacts.mkdir()
        manifest = {"nodes": {}, "metadata": {"marker": "first"}}
        (artifacts / "manifest.json").write_text(json.dumps(manifest))
        first = self.manager.register_baseline(self.project_id, artifacts, "prod")
        original_snapshot = Path(first["manifest_path"]).read_bytes()
        (artifacts / "manifest.json").write_text(json.dumps({"nodes": {}, "metadata": {"marker": "second"}}))
        second = self.manager.register_baseline(self.project_id, artifacts, "prod")
        self.assertNotEqual(first["id"], second["id"])
        self.assertEqual(Path(first["manifest_path"]).read_bytes(), original_snapshot)
        self.assertEqual(self.manager.baseline(self.project_id), second)
        self.assertEqual(self.manager.baseline(self.project_id, first["id"]), first)
        self.assertFalse(first["data_is_snapshot"])
        self.assertEqual(Path(first["manifest_path"]).stat().st_mode & 0o222, 0)
        restarted = WorkspaceManager(self.manager.data_dir)
        self.assertEqual(restarted.baseline(self.project_id, first["id"]), first)
        with self.assertRaises(KeyError):
            restarted.baseline(self.project_id, "missing")

    def test_baseline_rejects_symlinked_artifacts(self):
        artifacts = self.root / "artifacts"
        artifacts.mkdir()
        outside = self.root / "other.json"
        outside.write_text('{"nodes": {}}')
        (artifacts / "manifest.json").symlink_to(outside)
        with self.assertRaises(ValueError):
            self.manager.register_baseline(self.project_id, artifacts, "prod")
        self.assertIsNone(self.manager.baseline(self.project_id))

    def test_baseline_import_cannot_recursively_copy_its_destination(self):
        data_path = self.manager.project_data(self.project_id)
        (data_path / "manifest.json").write_text('{"nodes": {}}')
        with self.assertRaisesRegex(ValueError, "baseline destination"):
            self.manager.register_baseline(self.project_id, data_path, "prod")
        self.assertFalse((data_path / "baselines").exists())
        self.assertIsNone(self.manager.baseline(self.project_id))

    def test_imported_baseline_validates_project_and_preserves_local_manifest(self):
        artifacts = self.root / "artifacts"
        artifacts.mkdir()
        artifact = artifacts / "manifest.json"
        artifact.write_text('{"nodes": {}, "metadata": {"project_name": "sample", "marker": "local"}}')
        self.manager.register_manifest(self.project_id, artifact)
        artifact.write_text('{"nodes": {}, "metadata": {"project_name": "other"}}')
        with self.assertRaises(ValueError):
            self.manager.register_baseline(self.project_id, artifacts, "remote-prod")
        artifact.write_text('{"nodes": {}, "metadata": {"project_name": "sample", "marker": "remote"}}')
        imported = self.manager.register_baseline(self.project_id, artifacts, "remote-prod")
        self.assertEqual(imported["target"], "remote-prod")
        self.assertEqual(self.manager.manifest(self.project_id)["metadata"]["marker"], "local")

    def test_unknown_project_ids_never_become_paths(self):
        with self.assertRaises(KeyError):
            self.manager.project_data("../../outside")


if __name__ == "__main__":
    unittest.main()
