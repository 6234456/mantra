/** Presentation generics refine schema-generated fields; all actual wire fields come from JSON Schema. */
import type {
  Packages as WirePackages,
  PackagesPackageCase,
  PackagesMountedPackage,
  PackagesBinding,
  PackagesParameterSource,
  PackagesDocument,
  PackagesMigrationPreview,
} from '../generated/contract'
import type { Envelope } from '../types'

export type PackageEnvelope<T> = Omit<WirePackages, 'data'> & { data: T }
export type PackageCase = PackagesPackageCase
export type MountedPackage = PackagesMountedPackage
export type PackageBinding = PackagesBinding
export type ParameterSource = PackagesParameterSource
export type PackageDocument<T> = Omit<PackagesDocument, 'document'> & { document: Envelope<T> | null }
export type MigrationPreview = PackagesMigrationPreview
