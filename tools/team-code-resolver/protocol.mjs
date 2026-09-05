import crypto from "node:crypto";
import zlib from "node:zlib";
import {
  resolveAbility,
  resolveItem,
  resolveMove,
  resolveNature,
  resolveSpecies,
} from "./entity-map.mjs";

const CODE_PATTERN = /^[A-Z0-9]{10}$/;

export class OfficialProtocolError extends Error {}

export function normalizeCode(rawCode) {
  const code = String(rawCode || "").trim().replace(/\s+/g, "").toUpperCase();
  return CODE_PATTERN.test(code) ? code : undefined;
}

export function createApiRequestHash(pmc, dummy, token, sessionId, randomValue) {
  const random = randomValue ?? crypto.randomInt(0x1000, 0xffff);
  const inner = sha1(`${sessionId}@${token}@${dummy}`);
  const digest = md5(`${sha1(pmc)}-${random}-${inner}`);
  return `${random.toString(16).padStart(4, "0")}${digest}`;
}

export function encryptApiPayload(text, dummy, token, sessionId, udVer) {
  const first = md5(`${sessionId}$${token}$${dummy}`);
  const offset = Number(BigInt(dummy) % 32n);
  const rotated = `${first.slice(offset)}${first.slice(0, offset)}`;
  const key = Buffer.from(`${first}${md5(`${Number(udVer)}${rotated}`)}`, "hex");
  const iv = Buffer.from(md5(`${sessionId}=${dummy}=${token}`), "hex");
  const cipher = crypto.createCipheriv("aes-256-cbc", key, iv);
  return Buffer.concat([
    cipher.update(zlib.gzipSync(Buffer.from(text, "utf8"))),
    cipher.final(),
  ]).toString("base64");
}

export function decryptApiPayload(pmc, dummy, token, sessionId, requestUdVer) {
  const first = md5(`${sessionId}$${token}$${dummy}`);
  const offset = Number(BigInt(dummy) % 32n);
  const rotated = `${first.slice(offset)}${first.slice(0, offset)}`;
  const iv = Buffer.from(md5(`${sessionId}=${dummy}=${token}`), "hex");
  for (const udVer of [Number(requestUdVer) + 1, Number(requestUdVer)]) {
    try {
      const key = Buffer.from(`${first}${md5(`${udVer}${rotated}`)}`, "hex");
      const decipher = crypto.createDecipheriv("aes-256-cbc", key, iv);
      const zipped = Buffer.concat([
        decipher.update(Buffer.from(pmc, "base64")),
        decipher.final(),
      ]);
      return { text: zlib.gunzipSync(zipped).toString("utf8"), udVer };
    } catch {
      // Official responses use either the request or next user-data version.
    }
  }
  throw new OfficialProtocolError("Unable to decrypt the official team response");
}

export function mapOfficialTeam(code, payload) {
  const team = payload?.tng;
  if (!team || !Array.isArray(team.mem) || team.mem.length !== 6) {
    throw new OfficialProtocolError("Official team payload must contain exactly six members");
  }
  const items = new Map(
    (Array.isArray(team.itms) ? team.itms : []).map((entry) => [Number(entry.idx), Number(entry.i)]),
  );
  return {
    schemaVersion: 1,
    kind: "PokemonChampionsPublicTeam",
    code: normalizeCode(code),
    trainerName: typeof team.unam === "string" && team.unam.trim() ? team.unam.trim() : undefined,
    members: team.mem.map((member, index) => {
      const itemNumber = items.get(index);
      const moves = (Array.isArray(member.bf) ? member.bf : [])
        .map(Number)
        .filter((number) => number > 0)
        .map(resolveMove);
      if (moves.length < 1 || moves.length > 4) {
        throw new OfficialProtocolError(`Official member ${index + 1} has an invalid move list`);
      }
      const result = {
        speciesId: resolveSpecies(member.b0, member.b1),
        level: 50,
        gender: resolveGender(member.b2),
        natureId: resolveNature(member.b8),
        abilityId: resolveAbility(member.b5),
        statPoints: {
          hp: Number(member.b9),
          atk: Number(member.ba),
          def: Number(member.bb),
          spa: Number(member.bd),
          spd: Number(member.be),
          spe: Number(member.bc),
        },
        moveIds: moves,
      };
      if (itemNumber > 0) result.itemId = resolveItem(itemNumber);
      return result;
    }),
  };
}

function resolveGender(number) {
  switch (Number(number)) {
    case 0: return "male";
    case 1: return "female";
    case 2: return "genderless";
    default: return "unknown";
  }
}

function md5(value) {
  return crypto.createHash("md5").update(String(value), "utf8").digest("hex");
}

function sha1(value) {
  return crypto.createHash("sha1").update(String(value), "utf8").digest("hex");
}
