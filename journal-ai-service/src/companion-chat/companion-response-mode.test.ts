import assert from "node:assert/strict";
import test from "node:test";

import {
  crisisCue,
  responseModeDirective,
  RESPONSE_MODES,
  selectResponseMode,
} from "./companion-response-mode.js";
import type { HistoryTurn } from "./conversation-history.js";

// Held-out phrasings written for these tests, not taken from the eval dataset.
const crisisPositives = [
  "Mình muốn tự sát.",
  "minh muon tu sat",
  "Dạo này mình hay nghĩ tới chuyện tự tử.",
  "Tớ muốn kết liễu bản thân cho xong.",
  "Mình muốn tự hại.",
  "Mình lại tự làm hại bản thân tối qua.",
  "Tối qua mình cắt tay.",
  "Em lại cứa tay rồi.",
  "Mình cào tay đến chảy máu mới thôi.",
  "Mình đã gom đủ thuốc rồi.",
  "Mình định uống hết lọ thuốc ngủ của mẹ.",
  "Mình muốn nhảy lầu.",
  "Mình đang nghĩ có nên nhảy từ sân thượng xuống không.",
  "Mình không thiết sống nữa.",
  "Chẳng muốn sống nữa đâu.",
  "Sống làm gì nữa khi chẳng ai cần mình.",
  "Mình chẳng còn lý do gì để sống.",
  "Thà chết còn hơn phải đối mặt với ba mẹ.",
  "Chết quách đi cho rồi.",
  "Mình chỉ muốn chết thôi.",
  "Tôi thật sự muốn chết.",
  "Mình không muốn thức dậy nữa.",
  "Ước gì ngủ một giấc không bao giờ dậy.",
  "Mình muốn biến mất khỏi thế giới này.",
  "Nếu mình không còn trên đời chắc mọi người nhẹ nhõm hơn.",
  "Mình đã viết thư tuyệt mệnh rồi.",
  "Đây là lần cuối mình nhắn, coi như xong.",
  "Mình gửi lời tạm biệt nha, mình là gánh nặng của mọi người.",
  "Mình định kết thúc mọi thứ, chắc mọi người sẽ nhẹ nhõm.",
  "khong thiet song nua",
  "song lam gi nua",
  "minh chi muon chet",
  "Phim buồn quá, xem xong mình muốn tự tử luôn.",
];

const crisisNegatives = [
  "Cứ từ từ thôi, không vội.",
  "Mình đang từ từ học lại tiếng Anh.",
  "Cô giáo không dạy nữa nên lớp nghỉ.",
  "Con dao làm bếp nhà mình cùn quá.",
  "Bạn nói đúng trên câu đó đấy.",
  "Đói muốn chết, chưa ăn gì từ sáng.",
  "Trời nóng muốn chết luôn.",
  "Chán muốn chết, cuối tuần chẳng có gì làm.",
  "Học muốn chết mà vẫn không vào đầu.",
  "Lạnh muốn chết, quên mang áo khoác.",
  "Trận đấu lần cuối của mùa giải hay ghê.",
  "Nó lừa mình lần cuối rồi đó.",
  "Mình đã nói lời tạm biệt với người yêu cũ.",
  "Kết thúc mọi thứ với công việc này thôi, mình nghỉ việc.",
  "Mình muốn biến mất khỏi nhóm chat này quá.",
  "Phim có cảnh nhân vật chính tự tử ở cuối.",
  "Bản tin sáng nay nói về một vụ tự tử ở Hà Nội.",
  "Ông mình không còn trên đời nữa, mình nhớ ông lắm.",
  "Mình bị dao cắt vào tay lúc nấu cơm.",
  "Mình cắt tay áo cho ngắn bớt.",
  "Mình thích xem môn nhảy cầu ở Thế vận hội.",
  "Cười chết mất với clip này.",
  "Deadline giết mình mất thôi.",
  "Pin điện thoại chết rồi.",
  "Game này mình chết hoài ở màn ba.",
  "Mình sống ở Đà Nẵng ba năm rồi.",
  "Mình vừa uống thuốc cảm xong.",
  "Mệt muốn xỉu sau ca đêm.",
  "Thôi mình nghỉ, tạm biệt nha, mai gặp.",
  "Mình chỉ muốn ngủ một giấc thật dài cho đã.",
  "Sợ chết khiếp lúc xem phim ma.",
  "cu tu tu thoi",
];

void test("crisis cues: held-out positives give SAFETY", () => {
  assert.ok(crisisPositives.length >= 30);
  for (const sentence of crisisPositives)
    assert.equal(crisisCue(sentence), true, sentence);
});

void test("crisis cues: everyday words and hyperbole do not give SAFETY", () => {
  assert.ok(crisisNegatives.length >= 30);
  for (const sentence of crisisNegatives)
    assert.equal(crisisCue(sentence), false, sentence);
});

void test("help requests wrap up at once; venting does not", () => {
  for (const sentence of [
    "Giờ mình phải làm sao?",
    "Làm thế nào để bớt lo trước khi thi?",
    "Mình cần lời khuyên.",
    "Cho mình xin lời khuyên với.",
    "Khuyên mình đi, mình rối quá.",
    "Làm gì bây giờ hả bạn?",
    "Giúp mình với, mình không biết bắt đầu từ đâu.",
    "Có cách nào để ngủ được không?",
    "Mình viết code cả ngày mệt quá, cho mình lời khuyên.",
  ])
    assert.equal(
      selectResponseMode(sentence, []),
      "SUPPORT_AND_WRAP_UP",
      sentence,
    );
  for (const sentence of [
    "Chẳng ai giúp mình cả.",
    "Không có cách nào hết.",
    "Chẳng ai giải quyết gì cả.",
    "Mình tìm ở đâu cũng không có việc.",
    "Xem ở đâu cũng thấy nó.",
    "Làm sao mình chịu nổi nữa.",
    "Đi phỏng vấn năm chỗ rồi mà không chỗ nào nhận.",
  ])
    assert.equal(selectResponseMode(sentence, []), "LISTEN", sentence);
});

const longText = (seed: string) =>
  `${seed} ${"Mình kể thêm cho bạn nghe chuyện ở công ty dạo này nhé. ".repeat(20)}`;

void test("long user turns still reach wrap-up: the arc counts recent turns, not the byte-capped history", () => {
  const turns: HistoryTurn[] = [
    { role: "USER", text: longText("Ba tháng nay mình làm tới khuya.") },
    { role: "ASSISTANT", text: "Phần nào làm bạn mệt nhất?" },
    { role: "USER", text: longText("Sếp giao thêm dự án.") },
    { role: "ASSISTANT", text: "Dự án mới đè lên việc nào?" },
  ];
  assert.ok(turns.every((turn) => turn.text.length < 2_000));
  assert.equal(
    selectResponseMode(
      longText("Cuối tuần cũng phải trả lời tin nhắn."),
      turns,
    ),
    "SUPPORT_AND_WRAP_UP",
  );
});

void test("a question softened by a closing sentence keeps the arc open", () => {
  // Seen in the round-1 real run: the reply asked, then added a softener; the next answer must still count.
  assert.equal(
    selectResponseMode("Ổng nói mình vô dụng.", [
      { role: "USER", text: "Buồn." },
      { role: "ASSISTANT", text: "Buồn kiểu gì vậy, bạn kể thêm nha?" },
      { role: "USER", text: "Chuyện ở nhà." },
      { role: "ASSISTANT", text: "Chuyện ở nhà với ai vậy?" },
      { role: "USER", text: "Ba." },
      {
        role: "ASSISTANT",
        text: "Ba bạn hả? Tớ hỏi để hiểu đúng hơn nha.",
      },
    ]),
    "SUPPORT_AND_WRAP_UP",
  );
});

void test("every non-SAFETY directive yields to the crisis rule", () => {
  for (const mode of RESPONSE_MODES.filter((value) => value !== "SAFETY"))
    assert.match(
      responseModeDirective(mode),
      /crisis rule in the system prompt overrides this directive/u,
      mode,
    );
});

void test("modes ask only on missing info and close topics without relying on reply questions (MB-846b)", () => {
  const vent = (texts: string[]): HistoryTurn[] =>
    texts.flatMap((text) => [
      { role: "USER" as const, text },
      { role: "ASSISTANT" as const, text: "Tớ nghe bạn, chuyện đó mệt thật." },
    ]);
  const first = "Mình vừa bị đứa bạn thân phản bội, nó kể bí mật cho cả lớp.";
  const second = "Tụi mình chơi với nhau gần mười năm rồi.";
  assert.equal(selectResponseMode(first, []), "LISTEN");
  assert.equal(selectResponseMode(second, vent([first])), "LISTEN");
  assert.equal(
    selectResponseMode(
      "Giờ ai cũng biết hết, mình thấy nhục lắm.",
      vent([first, second]),
    ),
    "SUPPORT_AND_WRAP_UP",
  );
  // Missing info: a word or two, or the user asks to explore.
  assert.equal(selectResponseMode("Buồn.", []), "CLARIFY");
  assert.equal(selectResponseMode("Chuyện ở nhà.", vent(["Buồn."])), "CLARIFY");
  assert.equal(
    selectResponseMode("Dạo này buồn hoài, không biết tại sao.", []),
    "CLARIFY",
  );
  assert.equal(
    selectResponseMode("Hỏi mình đi, mình muốn nói thêm.", []),
    "CLARIFY",
  );
  // Thanks or feeling better closes the topic; the next message starts over.
  const wrapped = vent([
    first,
    second,
    "Giờ ai cũng biết hết, mình thấy nhục lắm.",
  ]);
  assert.equal(
    selectResponseMode("Cảm ơn nha, nói ra cũng nhẹ hơn chút.", wrapped),
    "LISTEN",
  );
  assert.equal(selectResponseMode("Ừ, để mình thử.", []), "LISTEN");
  assert.equal(
    selectResponseMode("Sếp mắng mình trước cả phòng.", [
      ...vent([first, second]),
      ...vent(["Cảm ơn nha, mình thấy đỡ hơn rồi."]),
    ]),
    "LISTEN",
  );
  // A negated "better" is not a closing.
  assert.equal(
    selectResponseMode(
      "Mình vẫn không thấy đỡ hơn gì cả.",
      vent([first, second]),
    ),
    "SUPPORT_AND_WRAP_UP",
  );
});

void test("only a deep wrap-up aims for 1000 to 1500 characters; LISTEN and CLARIFY stay short (MB-846b)", () => {
  assert.match(
    responseModeDirective("SUPPORT_AND_WRAP_UP"),
    /about 1000–1500 characters/u,
  );
  assert.match(responseModeDirective("LISTEN"), /No question is needed/u);
  assert.match(
    responseModeDirective("CLARIFY"),
    /exactly one specific question/u,
  );
  assert.doesNotMatch(responseModeDirective("LISTEN"), /1000/u);
});
